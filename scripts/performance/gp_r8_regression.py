"""Black-box upgrade regression on a disposable rooted emulator (no production data)."""

from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import re
import shlex
import sqlite3
import subprocess
from tempfile import TemporaryDirectory
import time
from xml.etree import ElementTree

from gp_device import ROOT, OUTPUT, PACKAGE, UI_DUMP, adb, benchmark_installed, ensure_rooted_emulator, find_control, launch


PHOTO_DIR = "/sdcard/Pictures/GalliPick_R8_QA"
VIDEO_DIR = "/sdcard/Movies/GalliPick_R8_QA"
PHOTO_A = "gp_r8_a.png"
PHOTO_B = "gp_r8_b.png"
PHOTO_C = "gp_r8_c.png"
VIDEO = "gp_r8_video.mp4"
DATA = f"/data/user/0/{PACKAGE}"
DUPLICATE_WORKER = "com.hebi.gallipick.data.DuplicateScanWorker"
OCR_WORKER = "com.hebi.gallipick.data.PhotoOcrWorker"
REPORT = OUTPUT / "regression.json"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def wait_label(*labels, seconds=45):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            adb("shell", "rm", "-f", UI_DUMP, timeout=15)
            adb("shell", "uiautomator", "dump", UI_DUMP, timeout=20)
            hierarchy = adb("shell", "cat", UI_DUMP, timeout=15)
            for label in labels:
                point = find_control(hierarchy, label)
                if point is not None:
                    return label, point
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ElementTree.ParseError):
            pass
        time.sleep(0.5)
    raise ValueError(f"Expected benchmark controls {labels!r} were not displayed.")


def tap(*labels, seconds=45):
    label, (x, y) = wait_label(*labels, seconds=seconds)
    adb("shell", "input", "tap", str(x), str(y))
    return label


def force_stop():
    adb("shell", "am", "force-stop", PACKAGE)


def fresh_install(apk, replace_existing=False):
    args = ["install", "--no-incremental", "-g"]
    if replace_existing:
        args.append("-r")
    adb(*args, str(apk))
    require(benchmark_installed(), "Expected benchmark package is not installed.")


def path_exists(path):
    return adb("shell", f"if [ -e {shlex.quote(path)} ]; then echo yes; fi") == "yes"


def create_ocr_photo(directory):
    # Render genuine synthetic bilingual text using the device's shipped CJK font.
    candidates = adb("shell", "ls", "/system/fonts").splitlines()
    fonts = [name for name in candidates if re.search(r"(?:NotoSansCJK|NotoSansKR).*?\.(?:ttc|otf|ttf)$", name)]
    require(bool(fonts), "No bundled CJK font found for the synthetic OCR fixture.")
    font = sorted(fonts, key=lambda name: ("Regular" not in name, name))[0]
    local_font = directory / font
    adb("pull", "/system/fonts/" + font, str(local_font))
    text_file = directory / "fixture.txt"
    text_file.write_text("RECEIPT 2026\n서울 여행", encoding="utf-8")
    image = directory / "fixture.png"
    subprocess.run(
        ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
         "-f", "lavfi", "-i", "color=c=white:s=1000x350",
         "-vf", f"drawtext=fontfile={local_font}:textfile={text_file}:fontsize=90:fontcolor=black:x=40:y=40:line_spacing=30",
         "-frames:v", "1", "-threads", "1", str(image)],
        check=True, timeout=30,
    )
    require(image.is_file() and image.stat().st_size > 1000, "Synthetic OCR photo was not rendered.")
    return image


def publish_fixture(source, remote):
    require(not path_exists(remote), f"Refusing to overwrite existing media: {remote}")
    adb("push", str(source), remote)
    adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
        "-d", "file://" + remote)
    name = Path(remote).name
    where = shlex.quote(f"_display_name='{name}'")
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        output = adb("shell", f"content query --uri content://media/external_primary/file --projection _id:_display_name --where {where}", timeout=15)
        if re.search(rf"(?:^|[,\s])_display_name={re.escape(name)}(?:$|[,\r\n])", output):
            require(len(re.findall(r"(?m)^Row:", output)) == 1, f"Expected exactly one indexed fixture: {name}")
            return sha256(source)
        time.sleep(1)
    raise ValueError(f"MediaStore did not index synthetic fixture {name}.")


@contextmanager
def read_database(relative_path):
    # The app is force-stopped first. Read only copies of its DB and any WAL.
    remote = f"{DATA}/{relative_path}"
    with TemporaryDirectory() as directory:
        local = Path(directory) / Path(relative_path).name
        require(path_exists(remote), f"Expected app database {relative_path} is missing.")
        adb("pull", remote, str(local))
        if path_exists(remote + "-wal"):
            adb("pull", remote + "-wal", str(local) + "-wal")
        connection = sqlite3.connect(local)
        try:
            connection.execute("PRAGMA query_only=ON")
            require(connection.execute("PRAGMA quick_check").fetchall() == [("ok",)],
                    f"Database integrity check failed: {relative_path}")
            yield connection
        finally:
            connection.close()


def preference_snapshot():
    root = ElementTree.fromstring(adb("shell", "cat", f"{DATA}/shared_prefs/filters.xml"))
    return {node.get("name"): node.get("value", node.text) for node in root if node.get("name")}


def database_snapshot():
    with read_database("databases/gallipick-local.db") as db:
        result = {
            "schema_version": db.execute("PRAGMA user_version").fetchone()[0],
            "media": dict(db.execute("SELECT displayName, uri FROM media_cache WHERE displayName LIKE 'gp_r8_%'")),
            "protected": sorted(row[0] for row in db.execute("SELECT uri FROM protected_media")),
            "hashes": dict(db.execute("SELECT m.displayName, h.sha256 FROM media_cache m JOIN media_hash h "
                                      "ON h.uri=m.uri AND h.fingerprint=m.fingerprint WHERE m.displayName LIKE 'gp_r8_%'")),
            "ocr": {name: (status, value) for name, status, value in db.execute(
                "SELECT m.displayName, o.status, o.recognizedText FROM media_cache m JOIN photo_ocr_index o "
                "ON o.uri=m.uri AND o.fingerprint=m.fingerprint WHERE m.displayName LIKE 'gp_r8_%'")},
            "scan_phase": db.execute("SELECT phase FROM scan_session WHERE id=1").fetchone(),
            "ocr_phase": db.execute("SELECT phase FROM photo_ocr_session WHERE id=1").fetchone(),
        }
    work_path = "no_backup/androidx.work.workdb"
    if not path_exists(f"{DATA}/{work_path}"):
        work_path = "databases/androidx.work.workdb"
    with read_database(work_path) as db:
        result["work"] = db.execute("SELECT id, worker_class_name, state FROM workspec ORDER BY id").fetchall()
    return result


def worker_success(snapshot, worker):
    return {work_id for work_id, name, state in snapshot["work"] if name == worker and state == 2}


def verify_duplicate_hashes(state, expected_hashes, stage):
    # DuplicateScanWorker.plan hashes only size groups containing multiple files.
    # The synthetic photos are identical; the solitary video is for preview and
    # original-byte preservation, not an exact-duplicate candidate.
    expected = {name: digest for name, digest in expected_hashes.items() if name != VIDEO}
    invalid = [name for name, digest in expected.items() if state["hashes"].get(name) != digest]
    require(not invalid, f"{stage} original-byte duplicate hashes are incorrect: {invalid!r}.")
    require(VIDEO not in state["hashes"], f"{stage} unexpectedly hashed a singleton video.")


def verify_original_files(remote_paths, expected_hashes):
    # Independently check every fixture, including media excluded by the scan.
    for name, digest in expected_hashes.items():
        actual = adb("shell", "sha256sum", remote_paths[name]).split()[0]
        require(actual == digest, f"Synthetic original was changed: {name}")


def verify_baseline(state, expected_hashes):
    require(state["schema_version"] == 8, "Expected existing Room schema version 8.")
    require(set(expected_hashes).issubset(state["media"]), "Baseline did not load every fixture.")
    require(state["media"][PHOTO_A] in state["protected"], "Baseline did not persist protection.")
    require(state["scan_phase"] == ("COMPLETE",), "Baseline duplicate scan did not complete.")
    verify_duplicate_hashes(state, expected_hashes, "Baseline")
    require(worker_success(state, DUPLICATE_WORKER), "Baseline duplicate Worker did not succeed.")


def verify_upgrade(before, after, preferences_before, preferences_after):
    require(preferences_before.get("file_list_view") == "true", "Baseline list-view setting was not saved.")
    require(preferences_after.get("file_list_view") == "true", "List-view setting was lost during update.")
    require(after["schema_version"] == before["schema_version"], "Room schema version changed during update.")
    require(before["media"] == after["media"], "Existing media identities were lost or changed during update.")
    require(before["protected"] == after["protected"], "Existing protection settings were lost during update.")
    require(before["hashes"] == after["hashes"], "Existing exact duplicate cache was lost during update.")
    require(worker_success(before, DUPLICATE_WORKER) <= worker_success(after, DUPLICATE_WORKER),
            "Previously completed WorkManager job is missing after update.")


def verify_final(before, after, expected_hashes):
    require(after["scan_phase"] == ("COMPLETE",), "Optimized duplicate scan did not complete.")
    require(after["ocr_phase"] == ("COMPLETE",), "Optimized OCR scan did not complete.")
    require(worker_success(after, DUPLICATE_WORKER) - worker_success(before, DUPLICATE_WORKER),
            "No new successful duplicate Worker in optimized app.")
    require(worker_success(after, OCR_WORKER), "Optimized OCR Worker did not succeed.")
    require(set(expected_hashes).issubset(after["media"]), "Optimized app did not load every fixture.")
    verify_duplicate_hashes(after, expected_hashes, "Optimized")
    require(after["protected"] == before["protected"], "Optimized analysis changed protection.")
    for name in (PHOTO_A, PHOTO_B, PHOTO_C):
        status, value = after["ocr"].get(name, ("MISSING", ""))
        compact = re.sub(r"\s+", "", value).upper()
        require(status == "SUCCESS" and "RECEIPT" in compact and "서울" in compact,
                f"Bundled bilingual OCR did not recognize the expected fixture text: {name}")


def open_files():
    tap("파일")
    wait_label("파일 조건 선택")


def run_duplicate_scan():
    tap("정리")
    tap("사진·동영상 중복 검사 열기", "같은 파일 찾기")
    tap("검사 시작", "다시 검사")
    wait_label("다시 검사", seconds=90)
    # Allow the WorkManager transaction to finish after the app session completes.
    time.sleep(2)


def run_ocr():
    open_files()
    chosen = tap("검색 범위 변경", "사진 속 글자")
    if chosen == "검색 범위 변경":
        tap("사진 속 글자")
    tap("사진 글자 분석 시작", "사진 다시 분석", "미분석·실패 사진 다시 분석")
    wait_label("사진 다시 분석", "미분석·실패 사진 다시 분석", seconds=120)
    time.sleep(2)


def main():
    require(adb("shell", "getprop", "ro.kernel.qemu") == "1", "Disposable emulator required.")
    require(not benchmark_installed(), "Refusing to replace a pre-existing benchmark installation.")
    require(not REPORT.exists(), "Refusing a stale regression report.")
    ensure_rooted_emulator()
    for folder in (PHOTO_DIR, VIDEO_DIR):
        require(not path_exists(folder), f"Test media directory already exists: {folder}")
        adb("shell", "mkdir", "-p", folder)
    baseline = OUTPUT / "baseline.apk"
    optimized = OUTPUT / "r8.apk"
    remote_paths = {name: f"{PHOTO_DIR}/{name}" for name in (PHOTO_A, PHOTO_B, PHOTO_C)}
    remote_paths[VIDEO] = f"{VIDEO_DIR}/{VIDEO}"
    with TemporaryDirectory() as temp:
        image = create_ocr_photo(Path(temp))
        clip = ROOT / "app/src/androidTest/assets/preview-baseline.mp4"
        require(image.stat().st_size != clip.stat().st_size,
                "The singleton video must have a different size from the duplicate photos.")
        expected = {}
        for name, source in ((PHOTO_A, image), (PHOTO_B, image), (VIDEO, clip)):
            expected[name] = publish_fixture(source, remote_paths[name])
        try:
            print("GP-R8-04: seed settings, protection and duplicate scan in baseline app.", flush=True)
            fresh_install(baseline)
            launch("COLD")
            open_files()
            tap("파일 추가 작업")
            tap("목록 보기")
            tap(PHOTO_A)
            wait_label("파일 상세")
            tap("정리에서 보호")
            wait_label("보호 해제")
            tap("닫기")
            run_duplicate_scan()
            force_stop()
            before = database_snapshot()
            preferences_before = preference_snapshot()
            verify_baseline(before, expected)
            verify_original_files(remote_paths, expected)

            print("GP-R8-04: replace baseline APK in place with the same-key optimized APK.", flush=True)
            fresh_install(optimized, replace_existing=True)
            launch("COLD")
            open_files()
            tap("파일 추가 작업")
            wait_label("격자 보기")
            adb("shell", "input", "keyevent", "4")
            tap(PHOTO_A)
            wait_label("보호 해제")
            tap("미리보기")
            wait_label("사진 미리보기: " + PHOTO_A)
            tap("미리보기 닫기")
            tap("닫기")
            tap(VIDEO)
            tap("미리보기")
            wait_label("영상 미리보기: " + VIDEO)
            tap("재생")
            wait_label("일시정지", seconds=30)
            tap("미리보기 닫기")
            tap("닫기")
            force_stop()
            after = database_snapshot()
            verify_upgrade(before, after, preferences_before, preference_snapshot())

            print("GP-R8-04: add a new duplicate and run optimized duplicate/OCR workers.", flush=True)
            expected[PHOTO_C] = publish_fixture(image, remote_paths[PHOTO_C])
            launch("COLD")
            run_duplicate_scan()
            run_ocr()
            force_stop()
            final = database_snapshot()
            verify_final(before, final, expected)
            verify_original_files(remote_paths, expected)

            report = {
                "source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                "baseline_apk_sha256": sha256(baseline),
                "optimized_apk_sha256": sha256(optimized),
                "android_api_level": int(adb("shell", "getprop", "ro.build.version.sdk")),
                "checks": ["same_key_in_place_update", "persisted_list_view", "room_integrity_and_media_identity",
                           "persisted_protection_and_duplicate_cache", "completed_work_history",
                           "photo_preview", "video_preview_and_playback", "new_duplicate_worker",
                           "new_bilingual_ocr_worker", "synthetic_original_sha256_unchanged"],
                "fixtures": len(expected),
                "not_covered": ["actual prior published version", "pending/periodic WorkManager upgrade",
                                "system trash/restore confirmation", "live Google login/App Check",
                                "Play Billing", "AdMob/UMP", "cloud backup/restore"],
            }
            REPORT.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
            print("GP-R8-04: upgrade and local regression report completed.", flush=True)
        finally:
            if benchmark_installed():
                adb("uninstall", PACKAGE)
                require(not benchmark_installed(), "Failed to remove isolated benchmark package.")
            adb("shell", "rm", "-f", UI_DUMP)


if __name__ == "__main__":
    main()

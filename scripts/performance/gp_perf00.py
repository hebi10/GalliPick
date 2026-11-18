#!/usr/bin/env python3
"""Host SQLite proxy for GP-PERF-00. NOT an Android/Room/MediaStore benchmark."""
import argparse
import json
import os
import platform
import sqlite3
import statistics
import time
import tracemalloc
from pathlib import Path

SIZES = (1000, 10000, 30000, 50000)
SELECT_ALL = "SELECT * FROM photo_perceptual_hash"
SELECT_KEY = "SELECT * FROM photo_perceptual_hash WHERE uri = ? AND fingerprint = ? LIMIT 1"
MEDIA_INSERT = ("INSERT INTO media_cache VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
SCHEMA = """
CREATE TABLE media_cache (
  uri TEXT NOT NULL PRIMARY KEY, id INTEGER NOT NULL, volume TEXT NOT NULL,
  displayName TEXT NOT NULL, kind TEXT NOT NULL, mimeType TEXT NOT NULL,
  relativePath TEXT NOT NULL, sizeBytes INTEGER NOT NULL, width INTEGER NOT NULL,
  height INTEGER NOT NULL, durationMs INTEGER NOT NULL, dateTakenMs INTEGER NOT NULL,
  dateAddedMs INTEGER NOT NULL, dateModifiedMs INTEGER NOT NULL,
  generationModified INTEGER NOT NULL, isFavorite INTEGER NOT NULL,
  isTrashed INTEGER NOT NULL, fingerprint TEXT NOT NULL
);
CREATE TABLE photo_perceptual_hash (
  uri TEXT NOT NULL, fingerprint TEXT NOT NULL, volumeVersion TEXT NOT NULL,
  phash TEXT, sharpnessScore REAL, exposureScore REAL,
  PRIMARY KEY(uri, fingerprint)
);
CREATE TABLE media_volume_version (
  volume TEXT NOT NULL PRIMARY KEY, version TEXT NOT NULL
);
"""

def uri(i):
    return f"content://media/external/images/media/{i}"

def fp(i):
    return f"{2048 + i % 1200}:{1750000000000 + i}:1"

def phash(i):
    return f"{i % (1 << 64):016x}"

def media(i):
    stamp = 1750000000000 + i
    return (uri(i), i, "external_primary", f"photo-{i:06d}.jpg", "PHOTO",
            "image/jpeg", "DCIM/Camera/", 2048 + i % 1200, 1920, 1080, 0,
            stamp, stamp, stamp, 1, 0, 0, fp(i))

def build_database(n):
    db = sqlite3.connect(":memory:")
    db.executescript(SCHEMA)
    for start in range(0, n, 2000):
        end = min(n, start + 2000)
        db.executemany(MEDIA_INSERT, (media(i) for i in range(start, end)))
        db.executemany("INSERT INTO photo_perceptual_hash VALUES (?,?,?,?,?,?)",
                       ((uri(i), fp(i), "epoch", phash(i), 8.3, 0.7)
                        for i in range(start, end)))
    db.execute("INSERT INTO media_volume_version VALUES ('external_primary','epoch')")
    db.commit()
    return db

def case(n):
    selected = [(uri(i), fp(i), "epoch", phash(i))
                for i in range(n - 10, n - 7)]
    retained = [(uri(i), fp(i), "epoch",
                 phash(i) if i == n - 1 else "ffffffffffffffff")
                for i in range(n - 7, n)]
    return selected, retained

def same_proof(row, expected):
    return (row is not None and row[3] is not None and
            (row[0], row[1], row[2], row[3]) == expected)

def legacy(db, selected, retained):
    queries = 0
    for expected in selected:
        rows = db.execute(SELECT_ALL).fetchall()
        queries += 1
        if not any(same_proof(row, expected) for row in rows):
            return None, queries
    rows = db.execute(SELECT_ALL).fetchall()
    queries += 1
    lookup = {(row[0], row[1], row[2]): row for row in rows}
    for expected in retained:
        if same_proof(lookup.get(expected[:3]), expected):
            return expected[0], queries
    return None, queries

def indexed(db, selected, retained):
    queries = 0
    for expected in selected:
        row = db.execute(SELECT_KEY, expected[:2]).fetchone()
        queries += 1
        if not same_proof(row, expected):
            return None, queries
    for expected in retained:
        row = db.execute(SELECT_KEY, expected[:2]).fetchone()
        queries += 1
        if same_proof(row, expected):
            return expected[0], queries
    return None, queries

def percent(values, percentile):
    values = sorted(values)
    position = (len(values) - 1) * percentile
    left = int(position)
    return values[left] + (values[min(left + 1, len(values) - 1)] - values[left]) * (position - left)

def times(values):
    return {"p50_ms": round(percent(values, 0.50), 3),
            "p95_ms": round(percent(values, 0.95), 3)}

def peak_bytes(action):
    tracemalloc.start()
    try:
        action()
        return tracemalloc.get_traced_memory()[1]
    finally:
        tracemalloc.stop()

def time_action(action, repetitions):
    readings = []
    for _ in range(repetitions):
        start = time.perf_counter_ns()
        action()
        readings.append((time.perf_counter_ns() - start) / 1_000_000)
    return times(readings)

def refresh_full_replace(db, n):
    """Proxy for unchanged-snapshot DELETE+INSERT+prune+epoch transaction."""
    db.execute("SAVEPOINT perf_refresh")
    try:
        db.execute("DELETE FROM media_cache")
        for start in range(0, n, 2000):
            db.executemany(MEDIA_INSERT,
                           (media(i) for i in range(start, min(n, start + 2000))))
        db.execute("DELETE FROM photo_perceptual_hash WHERE NOT EXISTS ("
                   "SELECT 1 FROM media_cache AS m "
                   "WHERE m.uri = photo_perceptual_hash.uri "
                   "AND m.fingerprint = photo_perceptual_hash.fingerprint "
                   "AND m.isTrashed = 0)")
        db.execute("DELETE FROM media_volume_version")
        db.execute("INSERT INTO media_volume_version VALUES ('external_primary','epoch')")
    finally:
        db.execute("ROLLBACK TO perf_refresh")
        db.execute("RELEASE perf_refresh")

def measure(n, repetitions):
    db = build_database(n)
    try:
        selected, retained = case(n)
        old = lambda: legacy(db, selected, retained)
        new = lambda: indexed(db, selected, retained)
        old_result, new_result = old(), new()
        assert old_result[0] == new_result[0] == uri(n - 1), "Survivor result changed"
        assert old_result[1] == 4 and new_result[1] == 10
        plan = [row[3] for row in db.execute("EXPLAIN QUERY PLAN " + SELECT_KEY,
                                               selected[0][:2])]
        assert any("INDEX" in step.upper() for step in plan), "Primary-key lookup lacks index"
        old()
        new()
        old_ms, new_ms = [], []
        for repeat in range(repetitions):
            operations = [(old, old_ms), (new, new_ms)]
            if repeat % 2:
                operations.reverse()
            for operation, samples in operations:
                start = time.perf_counter_ns()
                operation()
                samples.append((time.perf_counter_ns() - start) / 1_000_000)
        before = {**times(old_ms), "sql_statements": old_result[1],
                  "python_peak_bytes": peak_bytes(old)}
        after = {**times(new_ms), "sql_statements": new_result[1],
                 "python_peak_bytes": peak_bytes(new)}
        refresh_full_replace(db, n)
        refresh = time_action(lambda: refresh_full_replace(db, n), max(3, min(repetitions, 5)))
        return {"media_count": n, "legacy": before, "indexed": after,
                "legacy_full_replace": refresh, "proof_parity": True,
                "sqlite_index_plan": plan,
                "median_ratio": round(percent(old_ms, 0.5) / max(0.000001, percent(new_ms, 0.5)), 1)}
    finally:
        db.close()

def run(sizes, repetitions):
    if not sizes or any(n < 20 or n > 100000 for n in sizes) or not 3 <= repetitions <= 25:
        raise ValueError("Use 20–100000 synthetic media, 3–25 paired repetitions")
    return {
        "kind": "HOST_SQLITE_PROXY_NOT_ANDROID_ROOM",
        "environment": {"python": platform.python_version(),
                        "sqlite": sqlite3.sqlite_version,
                        "os": platform.system(), "architecture": platform.machine(),
                        "cpu_count": os.cpu_count()},
        "repetitions": repetitions,
        "scenario": "3 selected proofs, 7 survivor candidates, last retained original is valid",
        "limitations": [
            "Not an Android Room, MediaStore, Compose or battery measurement",
            "Python tracemalloc is not process RSS",
            "Full-replace baseline is host SQLite unchanged-snapshot proxy; no optimized refresh exists yet",
            "Median ratios must not be represented as real-world Android app acceleration",
        ],
        "results": [measure(n, repetitions) for n in sizes],
    }

def markdown(report):
    lines = [
        "# GP-PERF-00 host SQLite proxy",
        "",
        "**Synthetic benchmark. Not Android Room/MediaStore performance.**",
        "",
        "| Media | Legacy p50/p95 (ms) | Indexed p50/p95 (ms) | p50 ratio | SQL reads old/new | Python peak MiB old/new | Full replace p50 (ms) |",
        "| ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for x in report["results"]:
        a, b = x["legacy"], x["indexed"]
        lines.append(f"| {x['media_count']:,} | {a['p50_ms']:.3f}/{a['p95_ms']:.3f} | "
                     f"{b['p50_ms']:.3f}/{b['p95_ms']:.3f} | {x['median_ratio']:.1f}x | "
                     f"{a['sql_statements']}/{b['sql_statements']} | "
                     f"{a['python_peak_bytes']/1048576:.1f}/{b['python_peak_bytes']/1048576:.1f} | "
                     f"{x['legacy_full_replace']['p50_ms']:.1f} |")
    env = report["environment"]
    lines += ["", f"Python {env['python']}, SQLite {env['sqlite']}, "
              f"{env['os']}/{env['architecture']}, {report['repetitions']} paired repetitions.",
              "", "## Limitations"]
    lines += [f"- {item}" for item in report["limitations"]]
    return "\n".join(lines) + "\n"

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sizes", nargs="+", type=int, default=list(SIZES))
    parser.add_argument("--repetitions", type=int, default=7)
    parser.add_argument("--output", type=Path, default=Path("performance-output/report.json"))
    parser.add_argument("--markdown", type=Path, default=Path("performance-output/report.md"))
    args = parser.parse_args()
    data = run(args.sizes, args.repetitions)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.markdown.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    args.markdown.write_text(markdown(data), encoding="utf-8")
    print(markdown(data))

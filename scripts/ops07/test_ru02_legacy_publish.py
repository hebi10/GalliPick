"""RU-02 regression: legacy publisher must remain build/check only."""
import os
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/play-deploy.yml"
MARKER = "      - name: RU-02"


def guard_script(text):
    lines = text.splitlines()
    first = next(i for i, line in enumerate(lines) if line.startswith(MARKER))
    start = next(i + 1 for i in range(first, len(lines)) if lines[i] == "        run: |")
    script = []
    for line in lines[start:]:
        if line and not line.startswith("          "):
            break
        script.append(line[10:] if line else "")
    return "\n".join(script)


class LegacyPublishGuardTest(unittest.TestCase):
    def test_guard_runs_before_legacy_build_or_authentication(self):
        contents = WORKFLOW.read_text(encoding="utf-8")
        self.assertLess(contents.index(MARKER), contents.index("      - uses: actions/checkout@v4"))
        self.assertIn("exit 1", guard_script(contents))

    def test_approved_nonpublishing_modes(self):
        script = guard_script(WORKFLOW.read_text(encoding="utf-8"))
        for value in ["check","build"]:
            with self.subTest(value=value):
                proc = subprocess.run(["bash", "-c", script], text=True,
                    env={**os.environ, "LEGACY_MODE": value}, capture_output=True, timeout=10)
                self.assertEqual(proc.returncode, 0, proc.stderr)

    def test_legacy_publish_is_blocked(self):
        script = guard_script(WORKFLOW.read_text(encoding="utf-8"))
        for value in ["deploy"]:
            with self.subTest(value=value):
                proc = subprocess.run(["bash", "-c", script], text=True,
                    env={**os.environ, "LEGACY_MODE": value}, capture_output=True, timeout=10)
                self.assertNotEqual(proc.returncode, 0)


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import os
import subprocess
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
HELPER = BACKEND_ROOT / "scripts" / "lila_android_emulator_readiness.sh"
RUNNER = BACKEND_ROOT / "scripts" / "run_lila_android_live_e2e.sh"

FAKE_ADB = r"""#!/usr/bin/env bash
set -euo pipefail
args="$*"
mkdir -p "$ADB_TEST_STATE"
printf '%s\n' "$args" >> "$ADB_TEST_LOG"
count() {
  local name="$1"
  local file="$ADB_TEST_STATE/$name.count"
  local value=0
  if [ -f "$file" ]; then value="$(cat "$file")"; fi
  value=$((value + 1))
  printf '%s' "$value" > "$file"
  printf '%s' "$value"
}
case "$args" in
  "wait-for-device")
    n="$(count transport)"
    if [ "$n" -le "${ADB_TEST_TRANSPORT_FAILURES:-0}" ]; then exit 1; fi
    exit 0
    ;;
  "get-state") echo device ;;
  "shell getprop sys.boot_completed")
    n="$(count boot)"
    if [ "$n" -ge "${ADB_TEST_BOOT_AFTER:-1}" ]; then echo 1; else echo 0; fi
    ;;
  "shell cmd package list packages")
    n="$(count package)"
    if [ "${ADB_TEST_PACKAGE_NEVER_READY:-0}" = "1" ] || [ "$n" -lt "${ADB_TEST_PACKAGE_AFTER:-1}" ]; then exit 1; fi
    echo package:android
    ;;
  "shell dumpsys activity activities")
    n="$(count activity)"
    if [ "${ADB_TEST_ACTIVITY_NEVER_READY:-0}" = "1" ] || [ "$n" -lt "${ADB_TEST_ACTIVITY_AFTER:-1}" ]; then exit 1; fi
    echo ACTIVITY
    ;;
  "devices -l") echo "List of devices attached" ;;
  "shell service check package") echo "Service package: found" ;;
  "shell service check activity") echo "Service activity: found" ;;
  *) exit 2 ;;
esac
"""

def make_fake_adb(tmp_path: Path):
    bindir = tmp_path / "bin"
    bindir.mkdir()
    adb = bindir / "adb"
    adb.write_text(FAKE_ADB, encoding="utf-8")
    adb.chmod(0o755)
    state = tmp_path / "state"
    log = tmp_path / "adb.log"
    env = os.environ.copy()
    env.update({
        "PATH": str(bindir) + os.pathsep + env["PATH"],
        "ADB_TEST_STATE": str(state),
        "ADB_TEST_LOG": str(log),
        "LILA_ADB_TRANSPORT_WAIT_SECONDS": "4",
        "LILA_ANDROID_FRAMEWORK_WAIT_SECONDS": "4",
        "LILA_ADB_COMMAND_TIMEOUT_SECONDS": "1",
        "LILA_ADB_RETRY_DELAY_SECONDS": "0",
    })
    return env, state, log

def run_helper(tmp_path: Path, function: str, overrides: dict[str, str] | None = None):
    env, state, log = make_fake_adb(tmp_path)
    env.update(overrides or {})
    result = subprocess.run(
        ["bash", "-c", 'source "$1"; "$2"', "test", str(HELPER), function],
        env=env, text=True, capture_output=True, timeout=20, check=False,
    )
    return result, state, log

def test_framework_wait_requires_boot_and_both_system_services(tmp_path):
    result, state, log = run_helper(tmp_path, "lila_wait_for_android_framework", {
        "ADB_TEST_BOOT_AFTER": "2",
        "ADB_TEST_PACKAGE_AFTER": "2",
        "ADB_TEST_ACTIVITY_AFTER": "2",
    })
    assert result.returncode == 0, result.stderr
    calls = log.read_text(encoding="utf-8").splitlines()
    assert sum(line == "shell getprop sys.boot_completed" for line in calls) >= 2
    assert sum(line == "shell cmd package list packages" for line in calls) >= 2
    assert sum(line == "shell dumpsys activity activities" for line in calls) >= 2
    assert "Android framework ready" in result.stdout

def test_transport_wait_retries_transient_offline_state(tmp_path):
    result, state, log = run_helper(tmp_path, "lila_wait_for_adb_transport", {
        "ADB_TEST_TRANSPORT_FAILURES": "2",
    })
    assert result.returncode == 0, result.stderr
    calls = log.read_text(encoding="utf-8").splitlines()
    assert sum(line == "wait-for-device" for line in calls) == 3

def test_framework_wait_fails_closed_when_package_manager_never_responds(tmp_path):
    result, state, log = run_helper(tmp_path, "lila_wait_for_android_framework", {
        "ADB_TEST_PACKAGE_NEVER_READY": "1",
    })
    assert result.returncode != 0
    assert "Android framework services did not become ready" in result.stderr
    calls = log.read_text(encoding="utf-8").splitlines()
    assert "shell service check package" in calls
    assert "shell service check activity" in calls

def test_live_harness_waits_for_framework_without_restarting_adbd_after_ca_reboot():
    script = RUNNER.read_text(encoding="utf-8")
    assert 'source "$GITHUB_WORKSPACE/backend/scripts/lila_android_emulator_readiness.sh"' in script
    assert script.count("adb root") == 2
    final_reboot_section = script.rsplit("adb reboot", 1)[1]
    assert "lila_wait_for_android_framework" in final_reboot_section
    assert "adb root" not in final_reboot_section

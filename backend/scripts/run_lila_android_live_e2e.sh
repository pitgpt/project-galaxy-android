#!/usr/bin/env bash
set -euo pipefail
cd "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
source "$GITHUB_WORKSPACE/backend/scripts/lila_android_emulator_readiness.sh"

server_pid=""
server_log="${RUNNER_TEMP:?RUNNER_TEMP is required}/lila-android-e2e-server.log"
server_pid_file="$RUNNER_TEMP/lila-android-e2e-server.pid"
cleanup_lila_live_server() {
  adb reverse --remove tcp:8443 >/dev/null 2>&1 || true
  if [ -n "${server_pid:-}" ]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  rm -f "$server_pid_file"
}
trap cleanup_lila_live_server EXIT

adb root
adb disable-verity || true
adb reboot
lila_wait_for_adb_transport
adb root
lila_wait_for_adb_transport
adb remount

cert_hash="$(openssl x509 -in "$LILA_ANDROID_E2E_CA_CERT" -subject_hash_old -noout)"
adb push "$LILA_ANDROID_E2E_CA_CERT" "/system/etc/security/cacerts/${cert_hash}.0"
adb shell chown root:root "/system/etc/security/cacerts/${cert_hash}.0"
adb shell chmod 0644 "/system/etc/security/cacerts/${cert_hash}.0"
adb shell restorecon "/system/etc/security/cacerts/${cert_hash}.0"

# The CA is installed and remounted before this reboot. No further root access is
# needed: adb reverse, /data/local/tmp config access, and APK installation all
# work as the normal shell user. Avoid restarting adbd again before instrumentation.
adb reboot
lila_wait_for_android_framework

backend_python="${LILA_BACKEND_PYTHON:-python}"
nohup "$backend_python" backend/scripts/lila_android_e2e_server.py > "$server_log" 2>&1 < /dev/null &
server_pid=$!
printf '%s\n' "$server_pid" > "$server_pid_file"

server_ready=false
for attempt in $(seq 1 45); do
  if curl --max-time 5 -kfsS https://127.0.0.1:8443/ready >/dev/null; then
    server_ready=true
    break
  fi
  sleep 2
done
if [ "$server_ready" != true ] || ! kill -0 "$server_pid" 2>/dev/null; then
  cat "$server_log"
  echo "Authenticated HTTPS Lila Core did not remain ready inside the emulator step" >&2
  exit 1
fi

adb reverse tcp:8443 tcp:8443
reverse_list="$(adb reverse --list)"
printf '%s\n' "$reverse_list"
if ! printf '%s\n' "$reverse_list" | grep -Fq 'tcp:8443 tcp:8443'; then
  echo "ADB reverse mapping for the HTTPS Core endpoint is missing" >&2
  exit 1
fi

adb push "$LILA_ANDROID_E2E_CONFIG_PATH" /data/local/tmp/lila-android-e2e.json
adb shell chmod 0600 /data/local/tmp/lila-android-e2e.json
set +e
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.pitgpt.galaxy.data.remote.LiveLilaCoreAndroidE2EInstrumentedTest -Pandroid.testInstrumentationRunnerArguments.lilaLiveE2e=true
test_status=$?
set -e

if [ "$test_status" -eq 0 ]; then
  python - "$RUNNER_TEMP/lila-android-e2e/provider-requests.jsonl" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
records = [
    json.loads(line)
    for line in path.read_text(encoding="utf-8").splitlines()
    if line.strip()
]
if any(set(item) != {"method", "port", "path"} for item in records):
    raise SystemExit("Provider route diagnostics contain unexpected fields")
native_chat = any(
    item.get("port") == 11434 and item.get("path") == "/api/chat"
    for item in records
)
llama_cpp_discovery = any(
    item.get("method") == "GET"
    and item.get("port") == 8080
    and item.get("path") == "/v1/models"
    for item in records
)
llama_cpp_chat = any(
    item.get("method") == "POST"
    and item.get("port") == 8080
    and item.get("path") == "/v1/chat/completions"
    for item in records
)
if not native_chat or not llama_cpp_discovery or not llama_cpp_chat:
    raise SystemExit(
        "Live provider routing did not reach native Ollama 11434 and llama.cpp "
        "discovery/chat endpoints on 8080"
    )
print("Verified live model requests reached Ollama native 11434 and llama.cpp 8080")
PY
fi

adb shell rm -f /data/local/tmp/lila-android-e2e.json || true
exit "$test_status"

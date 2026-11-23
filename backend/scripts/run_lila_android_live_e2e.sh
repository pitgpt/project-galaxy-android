#!/usr/bin/env bash
set -euo pipefail

cd "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"

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
adb wait-for-device
adb root
adb remount

cert_hash="$(openssl x509 -in "$LILA_ANDROID_E2E_CA_CERT" -subject_hash_old -noout)"
adb push "$LILA_ANDROID_E2E_CA_CERT" "/system/etc/security/cacerts/${cert_hash}.0"
adb shell chown root:root "/system/etc/security/cacerts/${cert_hash}.0"
adb shell chmod 0644 "/system/etc/security/cacerts/${cert_hash}.0"
adb shell restorecon "/system/etc/security/cacerts/${cert_hash}.0"

adb reboot
adb wait-for-device
adb root
adb wait-for-device

nohup python backend/scripts/lila_android_e2e_server.py > "$server_log" 2>&1 < /dev/null &
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
gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.pitgpt.galaxy.data.remote.LiveLilaCoreAndroidE2EInstrumentedTest -Pandroid.testInstrumentationRunnerArguments.lilaLiveE2e=true
test_status=$?
set -e

adb shell rm -f /data/local/tmp/lila-android-e2e.json || true
exit "$test_status"

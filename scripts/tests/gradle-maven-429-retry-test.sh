#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
retry_script="$repository_root/scripts/gradle-maven-429-retry.sh"
temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/lila-gradle-retry-test.XXXXXX")"
trap 'rm -rf "$temporary_directory"' EXIT

fake_bin="$temporary_directory/bin"
mkdir -p "$fake_bin"

cat > "$fake_bin/fake-gradle" <<'FAKE_GRADLE'
#!/usr/bin/env bash
set -euo pipefail

count=0
if [[ -f "${FAKE_GRADLE_COUNT_FILE:?}" ]]; then
  count="$(cat "$FAKE_GRADLE_COUNT_FILE")"
fi
count=$((count + 1))
printf '%s' "$count" > "$FAKE_GRADLE_COUNT_FILE"

case "${FAKE_GRADLE_MODE:?}" in
  retry-once)
    if ((count == 1)); then
      echo "Could not GET 'https://repo.maven.apache.org/maven2/net/zetetic/sqlcipher-android/4.20.0/sqlcipher-android-4.20.0.pom'. Received status code 429 from server: Too Many Requests" >&2
      exit 1
    fi
    echo "BUILD SUCCESSFUL"
    ;;
  fail-without-throttling)
    echo "A deterministic compilation or test failure" >&2
    exit 7
    ;;
  other-repository-429)
    echo "Could not GET 'https://repo1.maven.org/maven2/example/example/1.0/example-1.0.pom'. Received status code 429 from server: Too Many Requests" >&2
    exit 9
    ;;
  always-maven-central-429)
    echo "Could not GET 'https://repo.maven.apache.org/maven2/example/example/1.0/example-1.0.pom'. Received status code 429 from server: Too Many Requests" >&2
    exit 23
    ;;
  *)
    echo "Unknown fake Gradle mode" >&2
    exit 64
    ;;
esac
FAKE_GRADLE

cat > "$fake_bin/sleep" <<'FAKE_SLEEP'
#!/usr/bin/env bash
exit 0
FAKE_SLEEP
chmod 700 "$fake_bin/fake-gradle" "$fake_bin/sleep"
export PATH="$fake_bin:$PATH"

# A Maven Central 429 is retried once and the successful Gradle exit is preserved.
export FAKE_GRADLE_COUNT_FILE="$temporary_directory/retry-once.count"
export FAKE_GRADLE_MODE=retry-once
bash "$retry_script" fake-gradle testDebugUnitTest
[[ "$(cat "$FAKE_GRADLE_COUNT_FILE")" == "2" ]]

# Ordinary compilation/test failures are returned immediately without retry.
export FAKE_GRADLE_COUNT_FILE="$temporary_directory/no-retry.count"
export FAKE_GRADLE_MODE=fail-without-throttling
set +e
bash "$retry_script" fake-gradle assembleDebug
status=$?
set -e
[[ "$status" -eq 7 ]]
[[ "$(cat "$FAKE_GRADLE_COUNT_FILE")" == "1" ]]

# A 429 from a repository other than Maven Central is not retried.
export FAKE_GRADLE_COUNT_FILE="$temporary_directory/other-repository.count"
export FAKE_GRADLE_MODE=other-repository-429
set +e
bash "$retry_script" fake-gradle connectedDebugAndroidTest
status=$?
set -e
[[ "$status" -eq 9 ]]
[[ "$(cat "$FAKE_GRADLE_COUNT_FILE")" == "1" ]]

# Persistent Maven Central throttling is bounded to exactly three attempts.
export FAKE_GRADLE_COUNT_FILE="$temporary_directory/bounded.count"
export FAKE_GRADLE_MODE=always-maven-central-429
set +e
bash "$retry_script" fake-gradle testDebugUnitTest
status=$?
set -e
[[ "$status" -eq 23 ]]
[[ "$(cat "$FAKE_GRADLE_COUNT_FILE")" == "3" ]]

echo "Gradle Maven Central 429 retry wrapper tests passed."

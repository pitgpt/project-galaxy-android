#!/usr/bin/env bash
set -euo pipefail

if (($# == 0)); then
  echo "Usage: bash scripts/gradle-maven-429-retry.sh <command> [args...]" >&2
  exit 64
fi

readonly MAX_ATTEMPTS=3
log_file="$(mktemp "${TMPDIR:-/tmp}/lila-gradle-maven-429.XXXXXX")"
chmod 600 "$log_file"
trap 'rm -f "$log_file"' EXIT

for ((attempt = 1; attempt <= MAX_ATTEMPTS; attempt++)); do
  : > "$log_file"
  set +e
  "$@" 2>&1 | tee "$log_file"
  command_status=${PIPESTATUS[0]}
  set -e

  if ((command_status == 0)); then
    exit 0
  fi

  # Retry only the observed transient failure: Maven Central returned HTTP 429
  # while Gradle was resolving an artifact. Do not retry test/build failures,
  # other repositories, TLS errors, or ambiguous application failures.
  if ! grep -Fq 'repo.maven.apache.org/maven2/' "$log_file" ||
     ! grep -Eiq 'status code 429|HTTP 429|Too Many Requests' "$log_file"; then
    exit "$command_status"
  fi

  if ((attempt == MAX_ATTEMPTS)); then
    echo "Maven Central continued returning HTTP 429 after ${MAX_ATTEMPTS} Gradle attempts; stopping." >&2
    exit "$command_status"
  fi

  delay_seconds=$((10 * (1 << (attempt - 1))))
  echo "Maven Central HTTP 429 detected during Gradle dependency resolution; retrying attempt $((attempt + 1))/$MAX_ATTEMPTS after ${delay_seconds}s." >&2
  sleep "$delay_seconds"
done

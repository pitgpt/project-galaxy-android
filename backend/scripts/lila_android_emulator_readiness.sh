#!/usr/bin/env bash
# Sourced by the live Android/Core E2E harness. Keep all waits bounded.
# adb wait-for-device confirms transport availability, not framework readiness.

_lila_validate_wait_settings() {
  local wait_seconds="$1"
  local command_timeout="$2"
  local retry_delay="$3"
  if ! [[ "$wait_seconds" =~ ^[1-9][0-9]*$ ]]; then
    echo "Invalid Android readiness wait budget" >&2
    return 1
  fi
  if ! [[ "$command_timeout" =~ ^[1-9][0-9]*$ ]]; then
    echo "Invalid ADB command timeout" >&2
    return 1
  fi
  if ! [[ "$retry_delay" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
    echo "Invalid Android readiness retry delay" >&2
    return 1
  fi
}

lila_wait_for_adb_transport() {
  local wait_seconds="${LILA_ADB_TRANSPORT_WAIT_SECONDS:-120}"
  local command_timeout="${LILA_ADB_COMMAND_TIMEOUT_SECONDS:-5}"
  local retry_delay="${LILA_ADB_RETRY_DELAY_SECONDS:-1}"
  local deadline
  _lila_validate_wait_settings "$wait_seconds" "$command_timeout" "$retry_delay" || return 1
  deadline=$((SECONDS + wait_seconds))
  while (( SECONDS < deadline )); do
    if timeout "${command_timeout}s" adb wait-for-device >/dev/null 2>&1 &&
       timeout "${command_timeout}s" adb get-state 2>/dev/null | grep -qx "device"; then
      return 0
    fi
    sleep "$retry_delay"
  done
  echo "Android emulator ADB transport did not become ready within ${wait_seconds}s" >&2
  timeout "${command_timeout}s" adb devices -l >&2 || true
  return 1
}

lila_wait_for_android_framework() {
  local wait_seconds="${LILA_ANDROID_FRAMEWORK_WAIT_SECONDS:-600}"
  local command_timeout="${LILA_ADB_COMMAND_TIMEOUT_SECONDS:-5}"
  local retry_delay="${LILA_ADB_RETRY_DELAY_SECONDS:-1}"
  local deadline
  local boot_completed=""
  _lila_validate_wait_settings "$wait_seconds" "$command_timeout" "$retry_delay" || return 1
  lila_wait_for_adb_transport || return 1
  deadline=$((SECONDS + wait_seconds))
  while (( SECONDS < deadline )); do
    boot_completed="$(
      timeout "${command_timeout}s" adb shell getprop sys.boot_completed 2>/dev/null |
        tr -d '\r' || true
    )"
    if [[ "$boot_completed" == "1" ]] &&
       timeout "${command_timeout}s" adb shell cmd package list packages >/dev/null 2>&1 &&
       timeout "${command_timeout}s" adb shell dumpsys activity activities >/dev/null 2>&1; then
      echo "Android framework ready: boot completed, Package Manager and Activity Manager respond"
      return 0
    fi
    sleep "$retry_delay"
  done
  echo "Android framework services did not become ready within ${wait_seconds}s" >&2
  timeout "${command_timeout}s" adb devices -l >&2 || true
  timeout "${command_timeout}s" adb shell getprop sys.boot_completed >&2 || true
  timeout "${command_timeout}s" adb shell service check package >&2 || true
  timeout "${command_timeout}s" adb shell service check activity >&2 || true
  return 1
}

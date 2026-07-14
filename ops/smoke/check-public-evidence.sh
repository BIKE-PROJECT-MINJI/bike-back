#!/usr/bin/env bash
set -euo pipefail

TARGET_DIR="${1:-ops/smoke/public-evidence}"

if [[ ! -d "$TARGET_DIR" ]]; then
  printf 'evidence directory not found: %s\n' "$TARGET_DIR" >&2
  exit 1
fi

SECRET_PATTERN='(AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|sk-[A-Za-z0-9_-]{16,}|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}|jdbc:postgresql://[^[:space:]]+:[^[:space:]@]+@|"(api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret)"[[:space:]]*:[[:space:]]*"[^"[:space:]]{8,}")'

if rg --line-number --pcre2 --ignore-case "$SECRET_PATTERN" "$TARGET_DIR"; then
  printf 'redaction check failed: secret-like value found\n' >&2
  exit 1
fi

for evidence in "$TARGET_DIR"/*.json; do
  if ! rg --quiet 'synthetic|deterministic fake provider' "$evidence"; then
    printf 'redaction check failed: fixture limitation missing in %s\n' "$evidence" >&2
    exit 1
  fi
done

printf 'redaction check passed: %s\n' "$TARGET_DIR"

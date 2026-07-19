#!/usr/bin/env bash
set -euo pipefail

readonly RUN_MATRIX_CONFIRMATION="${RUN_MATRIX_CONFIRMATION:-NO}"
[[ "$RUN_MATRIX_CONFIRMATION" == 'YES' ]] || {
  printf 'set RUN_MATRIX_CONFIRMATION=YES after cost and test scope approval\n' >&2
  exit 1
}
readonly RUN_ID="${RUN_ID:?set RUN_ID}"
readonly ACM_ARN="${ACM_ARN:?set ACM_ARN}"
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly STACK_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cleanup_required='NO'

cleanup() {
  local -r original_exit_code=$?
  local cleanup_exit_code=0
  trap - EXIT INT TERM
  if [[ "$cleanup_required" == 'YES' ]]; then
    ALLOW_AWS_DESTROY=YES bash "$SCRIPT_DIR/destroy-and-audit.sh" || cleanup_exit_code=$?
  fi
  if ((original_exit_code != 0)); then
    exit "$original_exit_code"
  fi
  exit "$cleanup_exit_code"
}
trap cleanup EXIT INT TERM

run_stage() {
  local -r stage="$1"
  local stage_exit_code=0
  local after_exit_code=0

  bash "$SCRIPT_DIR/collect-stage-observability.sh" "$stage" before
  STAGE="$stage" bash "$SCRIPT_DIR/run-k6-stage.sh" || stage_exit_code=$?
  bash "$SCRIPT_DIR/collect-stage-observability.sh" "$stage" after || after_exit_code=$?
  ((after_exit_code == 0)) || return "$after_exit_code"
  return "$stage_exit_code"
}

env \
  AWS_REGION="${AWS_REGION:-ap-northeast-2}" \
  DOMAIN_NAME="${DOMAIN_NAME:-api.gajabike.shop}" \
  APP_COUNT="${APP_COUNT:-1}" \
  RUN_ID="$RUN_ID" \
  ACM_ARN="$ACM_ARN" \
  bash "$SCRIPT_DIR/preflight.sh"
cleanup_required='YES'

export ALLOW_AWS_APPLY=YES
bash "$SCRIPT_DIR/prepare-control-plane.sh"
bash "$SCRIPT_DIR/build-and-upload-artifacts.sh"
readonly EXPECTED_BACKEND_IMAGE="gaja-back:$(git -C "$STACK_DIR/../../.." rev-parse HEAD)"
grep -Fq "BACKEND_IMAGE='$EXPECTED_BACKEND_IMAGE'" \
  "$STACK_DIR/.artifacts/$RUN_ID/app/role.env" || {
  printf 'backend artifact commit does not match validation commit\n' >&2
  exit 1
}
readonly PLAN_FILE="$(bash "$SCRIPT_DIR/plan-create-only.sh" | tail -n 1)"
terraform -chdir="$STACK_DIR" apply -input=false "$PLAN_FILE"
bash "$SCRIPT_DIR/verify-bootstrap-and-attach.sh"
bash "$SCRIPT_DIR/verify-https-edge.sh"

run_stage smoke
run_stage baseline-10
run_stage stress-25
run_stage ai-25

printf 'validation_matrix=PASS run_id=%s\n' "$RUN_ID"

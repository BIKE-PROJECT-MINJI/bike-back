#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PARENT_DIR="$(cd "$ROOT_DIR/.." && pwd)"
AI_ROUTE_DIR="${AI_ROUTE_DIR:-$PARENT_DIR/bike-ai-route}"

AWS_REGION="${AWS_REGION:-$(aws configure get region 2>/dev/null || true)}"
AWS_REGION="${AWS_REGION:-ap-northeast-2}"
PREFIX="${PREFIX:-bike-ulw-loadtest-$(date +%Y%m%d-%H%M%S)}"
INSTANCE_TYPE="${INSTANCE_TYPE:-t3.small}"
ALLOWED_INSTANCE_TYPES="${ALLOWED_INSTANCE_TYPES:-t3.micro t3.small}"
ROOT_VOLUME_SIZE_GB="${ROOT_VOLUME_SIZE_GB:-30}"
MAX_ROOT_VOLUME_SIZE_GB="${MAX_ROOT_VOLUME_SIZE_GB:-30}"
BEFORE_REF="${BEFORE_REF:-origin/main}"
RUN_DURATION="${RUN_DURATION:-30s}"
MAX_RUN_DURATION_SECONDS="${MAX_RUN_DURATION_SECONDS:-900}"
EVIDENCE_DIR="${EVIDENCE_DIR:-$ROOT_DIR/ops/loadtest/results/$PREFIX}"
SSH_USER="${SSH_USER:-ubuntu}"
K6_VERSION="${K6_VERSION:-v1.4.1}"
SECRET_ENV_FILE="${SECRET_ENV_FILE:-}"
K6_AI_ROUTE_VUS="${K6_AI_ROUTE_VUS:-0}"
K6_COURSE_MAP_READ_VUS="${K6_COURSE_MAP_READ_VUS:-0}"
K6_FREE_RIDE_VUS="${K6_FREE_RIDE_VUS:-1}"
K6_COURSE_FOLLOW_VUS="${K6_COURSE_FOLLOW_VUS:-1}"
K6_RIDE_FINALIZATION_VUS="${K6_RIDE_FINALIZATION_VUS:-0}"
K6_COURSE_READY_MAX_ATTEMPTS="${K6_COURSE_READY_MAX_ATTEMPTS:-80}"
K6_COURSE_READY_POLL_SECONDS="${K6_COURSE_READY_POLL_SECONDS:-0.1}"
K6_RIDE_FINALIZATION_REQUIRE_READY="${K6_RIDE_FINALIZATION_REQUIRE_READY:-false}"
K6_RIDE_FINALIZATION_READY_FAILURE_THRESHOLD="${K6_RIDE_FINALIZATION_READY_FAILURE_THRESHOLD:-}"
RUN_BEFORE="${RUN_BEFORE:-false}"
RESET_GRAPHHOPPER_CACHE="${RESET_GRAPHHOPPER_CACHE:-false}"
SSH_READY_MAX_ATTEMPTS="${SSH_READY_MAX_ATTEMPTS:-60}"
SSH_CONNECT_TIMEOUT_SECONDS="${SSH_CONNECT_TIMEOUT_SECONDS:-10}"
REMOTE_HEALTH_MAX_ATTEMPTS="${REMOTE_HEALTH_MAX_ATTEMPTS:-120}"
REMOTE_GRAPHHOPPER_READY_MAX_ATTEMPTS="${REMOTE_GRAPHHOPPER_READY_MAX_ATTEMPTS:-180}"
GRAPHHOPPER_CACHE_ARCHIVE_URL="${GRAPHHOPPER_CACHE_ARCHIVE_URL:-}"
GRAPHHOPPER_CACHE_ARCHIVE_FILE="${GRAPHHOPPER_CACHE_ARCHIVE_FILE:-}"
GRAPHHOPPER_CACHE_EXPORT="${GRAPHHOPPER_CACHE_EXPORT:-false}"
AWS_ROUTING_MODE="${AWS_ROUTING_MODE:-graphhopper}"
INSTANCE_TTL_SECONDS="${INSTANCE_TTL_SECONDS:-1800}"
MAX_INSTANCE_TTL_SECONDS="${MAX_INSTANCE_TTL_SECONDS:-3600}"
ALLOW_AFTER_K6_FAILURE="${ALLOW_AFTER_K6_FAILURE:-false}"
ALLOW_HIGH_VU_AWS_RUN="${ALLOW_HIGH_VU_AWS_RUN:-false}"
MAX_SINGLE_COMPOSE_TOTAL_VUS="${MAX_SINGLE_COMPOSE_TOTAL_VUS:-25}"
VALIDATE_ONLY="${VALIDATE_ONLY:-false}"
ALLOW_LONG_TTL_AWS_RUN="${ALLOW_LONG_TTL_AWS_RUN:-false}"
ALLOW_LARGE_VOLUME_AWS_RUN="${ALLOW_LARGE_VOLUME_AWS_RUN:-false}"
ALLOW_LONG_DURATION_AWS_RUN="${ALLOW_LONG_DURATION_AWS_RUN:-false}"

TMP_DIR=""
KEY_NAME=""
KEY_FILE=""
SG_ID=""
INSTANCE_ID=""
INSTANCE_PUBLIC_IP=""
REMOTE_SECRET_ENV=""
REMOTE_GRAPHHOPPER_CACHE_ARCHIVE=""
CLEANUP_DETAILS=""

log() {
  printf '[%s] %s\n' "$(date -Iseconds)" "$*"
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "missing required command: $1" >&2
    exit 10
  fi
}

is_non_negative_integer() {
  [[ "$1" =~ ^[0-9]+$ ]]
}

validate_instance_type() {
  local allowed
  case "$INSTANCE_TYPE" in
    *large*|*metal*)
      cat >&2 <<EOF
Refusing AWS run with INSTANCE_TYPE=$INSTANCE_TYPE.
Large, xlarge, and metal instances are disabled for BIKE cost protection and cannot be re-enabled with ALLOWED_INSTANCE_TYPES.
Use the separated AWS runbook with explicit architecture approval instead.
EOF
      exit 14
      ;;
  esac

  allowed=" $ALLOWED_INSTANCE_TYPES "
  if [[ "$allowed" != *" $INSTANCE_TYPE "* ]]; then
    cat >&2 <<EOF
Refusing AWS run with INSTANCE_TYPE=$INSTANCE_TYPE.
Allowed instance types for this low-cost BIKE validation wrapper: $ALLOWED_INSTANCE_TYPES.
Use the separated AWS runbook for larger validation.
EOF
    exit 14
  fi
}

duration_to_seconds() {
  local raw="$1"
  case "$raw" in
    *s)
      printf '%s\n' "${raw%s}"
      ;;
    *m)
      printf '%s\n' "$(( ${raw%m} * 60 ))"
      ;;
    *)
      echo "unsupported RUN_DURATION=$raw; use seconds or minutes, for example 30s or 2m" >&2
      exit 19
      ;;
  esac
}

validate_cost_guardrails() {
  local duration_seconds total_vus
  validate_instance_type

  case "$AWS_ROUTING_MODE" in
    graphhopper|fake)
      ;;
    *)
      echo "AWS_ROUTING_MODE must be either graphhopper or fake: $AWS_ROUTING_MODE" >&2
      exit 22
      ;;
  esac
  if [[ -n "$GRAPHHOPPER_CACHE_ARCHIVE_URL" && -n "$GRAPHHOPPER_CACHE_ARCHIVE_FILE" ]]; then
    echo "Use only one of GRAPHHOPPER_CACHE_ARCHIVE_URL or GRAPHHOPPER_CACHE_ARCHIVE_FILE." >&2
    exit 23
  fi
  if [[ -n "$GRAPHHOPPER_CACHE_ARCHIVE_FILE" && ! -f "$GRAPHHOPPER_CACHE_ARCHIVE_FILE" ]]; then
    echo "GRAPHHOPPER_CACHE_ARCHIVE_FILE does not exist: $GRAPHHOPPER_CACHE_ARCHIVE_FILE" >&2
    exit 24
  fi

  for value_name in ROOT_VOLUME_SIZE_GB MAX_ROOT_VOLUME_SIZE_GB INSTANCE_TTL_SECONDS MAX_INSTANCE_TTL_SECONDS MAX_RUN_DURATION_SECONDS \
    K6_AI_ROUTE_VUS K6_COURSE_MAP_READ_VUS K6_FREE_RIDE_VUS K6_COURSE_FOLLOW_VUS K6_RIDE_FINALIZATION_VUS MAX_SINGLE_COMPOSE_TOTAL_VUS; do
    if ! is_non_negative_integer "${!value_name}"; then
      echo "$value_name must be a non-negative integer: ${!value_name}" >&2
      exit 18
    fi
  done

  total_vus=$((K6_AI_ROUTE_VUS + K6_COURSE_MAP_READ_VUS + K6_FREE_RIDE_VUS + K6_COURSE_FOLLOW_VUS + K6_RIDE_FINALIZATION_VUS))
  if (( total_vus > MAX_SINGLE_COMPOSE_TOTAL_VUS )) && [[ "$ALLOW_HIGH_VU_AWS_RUN" != "true" ]]; then
    cat >&2 <<EOF
Refusing AWS run with total VUs=$total_vus.
Runs above $MAX_SINGLE_COMPOSE_TOTAL_VUS VUs require explicit user approval and ALLOW_HIGH_VU_AWS_RUN=true because they can create provider/cost pressure.
EOF
    exit 15
  fi
  if (( INSTANCE_TTL_SECONDS > MAX_INSTANCE_TTL_SECONDS )) && [[ "$ALLOW_LONG_TTL_AWS_RUN" != "true" ]]; then
    cat >&2 <<EOF
Refusing AWS run with INSTANCE_TTL_SECONDS=$INSTANCE_TTL_SECONDS.
Runs above $MAX_INSTANCE_TTL_SECONDS seconds require explicit user approval and ALLOW_LONG_TTL_AWS_RUN=true.
EOF
    exit 16
  fi
  if (( ROOT_VOLUME_SIZE_GB > MAX_ROOT_VOLUME_SIZE_GB )) && [[ "$ALLOW_LARGE_VOLUME_AWS_RUN" != "true" ]]; then
    cat >&2 <<EOF
Refusing AWS run with ROOT_VOLUME_SIZE_GB=$ROOT_VOLUME_SIZE_GB.
Volumes above $MAX_ROOT_VOLUME_SIZE_GB GiB require explicit user approval and ALLOW_LARGE_VOLUME_AWS_RUN=true.
EOF
    exit 17
  fi
  duration_seconds="$(duration_to_seconds "$RUN_DURATION")"
  if ! is_non_negative_integer "$duration_seconds"; then
    echo "RUN_DURATION must resolve to non-negative seconds: $RUN_DURATION" >&2
    exit 20
  fi
  if (( duration_seconds > MAX_RUN_DURATION_SECONDS )) && [[ "$ALLOW_LONG_DURATION_AWS_RUN" != "true" ]]; then
    cat >&2 <<EOF
Refusing AWS run with RUN_DURATION=$RUN_DURATION.
Runs above $MAX_RUN_DURATION_SECONDS seconds require explicit user approval and ALLOW_LONG_DURATION_AWS_RUN=true.
EOF
    exit 21
  fi
}

write_preflight_receipt() {
  local duration_seconds run_before_json total_vus
  run_before_json=false
  if [[ "$RUN_BEFORE" == "true" ]]; then
    run_before_json=true
  fi
  duration_seconds="$(duration_to_seconds "$RUN_DURATION")"
  total_vus=$((K6_AI_ROUTE_VUS + K6_COURSE_MAP_READ_VUS + K6_FREE_RIDE_VUS + K6_COURSE_FOLLOW_VUS + K6_RIDE_FINALIZATION_VUS))
  {
    echo "{"
    echo "  \"prefix\": \"$PREFIX\","
    echo "  \"region\": \"$AWS_REGION\","
    echo "  \"instanceType\": \"$INSTANCE_TYPE\","
    echo "  \"allowedInstanceTypes\": \"$ALLOWED_INSTANCE_TYPES\","
    echo "  \"rootVolumeSizeGb\": $ROOT_VOLUME_SIZE_GB,"
    echo "  \"maxRootVolumeSizeGb\": $MAX_ROOT_VOLUME_SIZE_GB,"
    echo "  \"runDuration\": \"$RUN_DURATION\","
    echo "  \"runDurationSeconds\": $duration_seconds,"
    echo "  \"maxRunDurationSeconds\": $MAX_RUN_DURATION_SECONDS,"
    echo "  \"routingMode\": \"$AWS_ROUTING_MODE\","
    echo "  \"graphhopperCacheArchiveUrlProvided\": $([[ -n "$GRAPHHOPPER_CACHE_ARCHIVE_URL" ]] && echo true || echo false),"
    echo "  \"graphhopperCacheArchiveFileProvided\": $([[ -n "$GRAPHHOPPER_CACHE_ARCHIVE_FILE" ]] && echo true || echo false),"
    echo "  \"runBefore\": $run_before_json,"
    echo "  \"instanceTtlSeconds\": $INSTANCE_TTL_SECONDS,"
    echo "  \"maxInstanceTtlSeconds\": $MAX_INSTANCE_TTL_SECONDS,"
    echo "  \"totalVus\": $total_vus,"
    echo "  \"maxSingleComposeTotalVus\": $MAX_SINGLE_COMPOSE_TOTAL_VUS,"
    echo "  \"aiRouteDir\": \"$AI_ROUTE_DIR\","
    echo "  \"createdAt\": \"$(date -Iseconds)\""
    echo "}"
  } > "$EVIDENCE_DIR/C000-aws-preflight.json"
}

add_cleanup_detail() {
  CLEANUP_DETAILS+="- $1: $2"$'\n'
}

aws_retry() {
  local attempt
  for attempt in 1 2 3; do
    "$@" && return 0
    sleep $((attempt * 5))
  done
  "$@"
}

cleanup() {
  local status=$?
  set +e

  if [[ -n "$REMOTE_SECRET_ENV" && -n "$INSTANCE_PUBLIC_IP" && -f "$KEY_FILE" ]]; then
    ssh_run "rm -f '$REMOTE_SECRET_ENV'" >/dev/null 2>&1
    add_cleanup_detail "remote_secret_env_removed" "$?"
  fi
  if [[ -n "$REMOTE_GRAPHHOPPER_CACHE_ARCHIVE" && -n "$INSTANCE_PUBLIC_IP" && -f "$KEY_FILE" ]]; then
    ssh_run "rm -f '$REMOTE_GRAPHHOPPER_CACHE_ARCHIVE'" >/dev/null 2>&1
    add_cleanup_detail "remote_graphhopper_cache_archive_removed" "$?"
  fi

  if [[ -n "$INSTANCE_ID" ]]; then
    log "terminating EC2 instance $INSTANCE_ID"
    aws_retry aws ec2 terminate-instances --region "$AWS_REGION" --instance-ids "$INSTANCE_ID" >/dev/null
    add_cleanup_detail "terminate_instances_exit" "$?"
    aws_retry aws ec2 wait instance-terminated --region "$AWS_REGION" --instance-ids "$INSTANCE_ID"
    add_cleanup_detail "instance_terminated_wait_exit" "$?"
    local instance_state
    instance_state="$(aws ec2 describe-instances --region "$AWS_REGION" --instance-ids "$INSTANCE_ID" --query 'Reservations[0].Instances[0].State.Name' --output text 2>/dev/null)"
    add_cleanup_detail "instance_state_after_cleanup" "${instance_state:-unknown}"
  fi

  if [[ -n "$SG_ID" ]]; then
    log "deleting security group $SG_ID"
    aws_retry aws ec2 delete-security-group --region "$AWS_REGION" --group-id "$SG_ID" >/dev/null
    add_cleanup_detail "delete_security_group_exit" "$?"
    aws ec2 describe-security-groups --region "$AWS_REGION" --group-ids "$SG_ID" >/dev/null 2>&1
    add_cleanup_detail "security_group_describe_after_delete_exit" "$?"
  fi

  if [[ -n "$KEY_NAME" ]]; then
    log "deleting key pair $KEY_NAME"
    aws_retry aws ec2 delete-key-pair --region "$AWS_REGION" --key-name "$KEY_NAME" >/dev/null
    add_cleanup_detail "delete_key_pair_exit" "$?"
    aws ec2 describe-key-pairs --region "$AWS_REGION" --key-names "$KEY_NAME" >/dev/null 2>&1
    add_cleanup_detail "key_pair_describe_after_delete_exit" "$?"
  fi

  if [[ -n "$TMP_DIR" ]]; then
    rm -rf "$TMP_DIR"
    add_cleanup_detail "local_tmp_removed_exit" "$?"
  fi

  mkdir -p "$EVIDENCE_DIR"
  {
    echo "# AWS cleanup receipt"
    echo
    echo "- prefix: $PREFIX"
    echo "- region: $AWS_REGION"
    echo "- instance_id: ${INSTANCE_ID:-}"
    echo "- security_group_id: ${SG_ID:-}"
    echo "- key_name: ${KEY_NAME:-}"
    echo "- exit_status: $status"
    echo "- cleanup_finished_at: $(date -Iseconds)"
    echo
    echo "## cleanup verification"
    printf '%s' "$CLEANUP_DETAILS"
  } > "$EVIDENCE_DIR/C002-aws-cleanup.md"

  exit "$status"
}
trap cleanup EXIT

package_tree() {
  local label="$1"
  local backend_source="$2"
  local package_dir="$TMP_DIR/package-$label"
  local tarball="$TMP_DIR/$label.tgz"

  mkdir -p "$package_dir/dev"

  if [[ "$backend_source" == "git:$BEFORE_REF" ]]; then
    mkdir -p "$package_dir/dev/bike-back"
    git -C "$ROOT_DIR" archive "$BEFORE_REF" | tar -x -C "$package_dir/dev/bike-back"
    cp "$ROOT_DIR/ops/loadtest/k6/ai-route-graphhopper-100-users.js" \
      "$package_dir/dev/bike-back/ops/loadtest/k6/ai-route-graphhopper-100-users.js"
  else
    local backend_rsync_args=(
      rsync -a --delete
      --exclude .git
      --exclude .gradle
      --include '.env.example'
      --include '.env.test.example'
      --exclude '.env'
      --exclude '.env.*'
      --exclude build
      --exclude 'ops/loadtest/results'
    )
    if [[ "$AWS_ROUTING_MODE" == "fake" ]]; then
      backend_rsync_args+=(
        --exclude 'ops/graphhopper/data'
        --exclude 'ops/graphhopper/graphhopper-web-*.jar'
      )
    fi
    backend_rsync_args+=("$ROOT_DIR/" "$package_dir/dev/bike-back/")
    "${backend_rsync_args[@]}"
  fi

  rsync -a --delete \
    --exclude .git \
    --include '.env.example' \
    --exclude '.env' \
    --exclude '.env.*' \
    --exclude .venv \
    --exclude .pytest_cache \
    --exclude '__pycache__' \
    --exclude '*.pyc' \
    "$AI_ROUTE_DIR/" "$package_dir/dev/bike-ai-route/"

  tar -C "$package_dir" -czf "$tarball" dev
  printf '%s\n' "$tarball"
}

ssh_run() {
  ssh \
    -o BatchMode=yes \
    -o ConnectTimeout="$SSH_CONNECT_TIMEOUT_SECONDS" \
    -o StrictHostKeyChecking=no \
    -o UserKnownHostsFile=/dev/null \
    -i "$KEY_FILE" \
    "$SSH_USER@$INSTANCE_PUBLIC_IP" "$@"
}

scp_to_instance() {
  scp \
    -o BatchMode=yes \
    -o ConnectTimeout="$SSH_CONNECT_TIMEOUT_SECONDS" \
    -o StrictHostKeyChecking=no \
    -o UserKnownHostsFile=/dev/null \
    -i "$KEY_FILE" "$1" \
    "$SSH_USER@$INSTANCE_PUBLIC_IP:$2"
}

scp_from_instance() {
  scp \
    -o BatchMode=yes \
    -o ConnectTimeout="$SSH_CONNECT_TIMEOUT_SECONDS" \
    -o StrictHostKeyChecking=no \
    -o UserKnownHostsFile=/dev/null \
    -i "$KEY_FILE" \
    "$SSH_USER@$INSTANCE_PUBLIC_IP:$1" "$2"
}

scp_from_instance_optional() {
  if ! scp_from_instance "$1" "$2"; then
    echo "optional remote evidence missing: $1" >&2
  fi
}

run_remote_case() {
  local label="$1"
  local tarball="$2"
  local remote_tar="/home/$SSH_USER/$label.tgz"
  local remote_dir="/home/$SSH_USER/bike-loadtest/$label"
  local test_id="$PREFIX-$label"

  log "uploading $label bundle"
  scp_to_instance "$tarball" "$remote_tar"

  log "running $label compose+k6"
  local remote_status
  set +e
  ssh_run "LABEL='$label' REMOTE_TAR='$remote_tar' REMOTE_DIR='$remote_dir' TEST_ID='$test_id' RUN_DURATION='$RUN_DURATION' REMOTE_SECRET_ENV='$REMOTE_SECRET_ENV' REMOTE_GRAPHHOPPER_CACHE_ARCHIVE='$REMOTE_GRAPHHOPPER_CACHE_ARCHIVE' K6_AI_ROUTE_VUS='$K6_AI_ROUTE_VUS' K6_COURSE_MAP_READ_VUS='$K6_COURSE_MAP_READ_VUS' K6_FREE_RIDE_VUS='$K6_FREE_RIDE_VUS' K6_COURSE_FOLLOW_VUS='$K6_COURSE_FOLLOW_VUS' K6_RIDE_FINALIZATION_VUS='$K6_RIDE_FINALIZATION_VUS' K6_COURSE_READY_MAX_ATTEMPTS='$K6_COURSE_READY_MAX_ATTEMPTS' K6_COURSE_READY_POLL_SECONDS='$K6_COURSE_READY_POLL_SECONDS' K6_RIDE_FINALIZATION_REQUIRE_READY='$K6_RIDE_FINALIZATION_REQUIRE_READY' K6_RIDE_FINALIZATION_READY_FAILURE_THRESHOLD='$K6_RIDE_FINALIZATION_READY_FAILURE_THRESHOLD' REMOTE_HEALTH_MAX_ATTEMPTS='$REMOTE_HEALTH_MAX_ATTEMPTS' REMOTE_GRAPHHOPPER_READY_MAX_ATTEMPTS='$REMOTE_GRAPHHOPPER_READY_MAX_ATTEMPTS' RESET_GRAPHHOPPER_CACHE='$RESET_GRAPHHOPPER_CACHE' GRAPHHOPPER_CACHE_ARCHIVE_URL='$GRAPHHOPPER_CACHE_ARCHIVE_URL' GRAPHHOPPER_CACHE_EXPORT='$GRAPHHOPPER_CACHE_EXPORT' AWS_ROUTING_MODE='$AWS_ROUTING_MODE' bash -s" <<'REMOTE'
	set -Eeuo pipefail
	mkdir -p "$REMOTE_DIR"
	tar -xzf "$REMOTE_TAR" -C "$REMOTE_DIR"
	cd "$REMOTE_DIR/dev/bike-back"
	mkdir -p ops/loadtest/results
	COMPOSE_FILES="-f docker-compose.test.yml"
	if [[ "$AWS_ROUTING_MODE" == "fake" ]]; then
	  COMPOSE_FILES="$COMPOSE_FILES -f docker-compose.test.fake-routing.yml"
	fi
	write_remote_stage() {
	  printf '%s %s\n' "$(date -Iseconds)" "$1" >> ops/loadtest/results/"$TEST_ID"-remote-stage.txt
	}
	write_remote_stage "unpacked"
	chmod +x ./gradlew
	cp .env.test.example .env.test
	chmod 600 .env.test
	remote_cleanup() {
	  local remote_status=$?
	  set +e
	  mkdir -p ops/loadtest/results
	  write_remote_stage "cleanup remote_status=$remote_status"
	  echo "$remote_status" > ops/loadtest/results/"$TEST_ID"-remote-exit-code.txt
	  if [[ "$remote_status" != "0" ]]; then
	    docker compose --env-file .env.test $COMPOSE_FILES ps \
	      > ops/loadtest/results/"$TEST_ID"-compose-ps.txt 2>&1 || true
	    docker compose --env-file .env.test $COMPOSE_FILES logs --tail=300 bike-back \
	      > ops/loadtest/results/"$TEST_ID"-bike-back-tail.log 2>&1 || true
	    docker compose --env-file .env.test $COMPOSE_FILES logs --tail=300 ai-route-worker graphhopper \
	      > ops/loadtest/results/"$TEST_ID"-routing-logs.txt 2>&1 || true
	    cp /tmp/"$LABEL"-health.json ops/loadtest/results/"$TEST_ID"-health.json 2>/dev/null || true
	    cp /tmp/"$LABEL"-health.err ops/loadtest/results/"$TEST_ID"-health.err 2>/dev/null || true
	    cp /tmp/"$LABEL"-graphhopper-ready.err ops/loadtest/results/"$TEST_ID"-graphhopper-ready.err 2>/dev/null || true
	  fi
	  if [[ "$RESET_GRAPHHOPPER_CACHE" == "true" ]]; then
	    docker compose --env-file .env.test $COMPOSE_FILES down -v >/dev/null 2>&1 || true
	  else
	    docker compose --env-file .env.test $COMPOSE_FILES down >/dev/null 2>&1 || true
	  fi
	  rm -f .env.test "$REMOTE_SECRET_ENV" "$REMOTE_GRAPHHOPPER_CACHE_ARCHIVE"
	  exit "$remote_status"
	}
	trap remote_cleanup EXIT
	if [[ -n "$REMOTE_SECRET_ENV" && -f "$REMOTE_SECRET_ENV" ]]; then
	  grep -E '^(GEMINI_API_KEY|GEMINI_MODEL|GOOGLE_MODEL|GOOGLE_API_KEY|OPENAI_API_KEY|OPENAI_MODEL|GRAPHHOPPER_API_KEY|KAKAO_LOCAL_REST_API_KEY|KAKAO_MOBILITY_REST_API_KEY)=' "$REMOTE_SECRET_ENV" >> .env.test || true
	  chmod 600 "$REMOTE_SECRET_ENV" .env.test
	fi
write_remote_stage "bootJar_start"
./gradlew --no-daemon bootJar --console=plain
write_remote_stage "bootJar_done"
if [[ "$RESET_GRAPHHOPPER_CACHE" == "true" ]]; then
  write_remote_stage "compose_down_with_volume_reset"
  docker compose --env-file .env.test $COMPOSE_FILES down -v >/dev/null 2>&1 || true
else
  write_remote_stage "compose_down_keep_volumes"
  docker compose --env-file .env.test $COMPOSE_FILES down >/dev/null 2>&1 || true
fi

restore_graphhopper_cache() {
  if [[ "$AWS_ROUTING_MODE" == "fake" ]]; then
    write_remote_stage "cache_restore_skipped_fake_routing"
    return 0
  fi
  if [[ -n "$REMOTE_GRAPHHOPPER_CACHE_ARCHIVE" ]]; then
    echo "restore_graphhopper_cache source=remote_file"
    write_remote_stage "cache_restore_start remote_file"
    docker compose --env-file .env.test $COMPOSE_FILES run -T --rm --no-deps \
      --volume "$REMOTE_GRAPHHOPPER_CACHE_ARCHIVE:/cache.tgz:ro" \
      --entrypoint sh graphhopper-prepare -c \
      "set -eu; mkdir -p /data; tar -xzf /cache.tgz -C /data; test -d /data/graph-cache" \
      < /dev/null
    write_remote_stage "cache_restore_done remote_file"
    return 0
  fi
  if [[ -z "$GRAPHHOPPER_CACHE_ARCHIVE_URL" ]]; then
    write_remote_stage "cache_restore_skipped"
    return 0
  fi
  echo "restore_graphhopper_cache source=$GRAPHHOPPER_CACHE_ARCHIVE_URL"
  write_remote_stage "cache_restore_start"
  docker compose --env-file .env.test $COMPOSE_FILES run -T --rm --no-deps --entrypoint sh graphhopper-prepare -c \
    "set -eu; mkdir -p /data; curl -fsSL '$GRAPHHOPPER_CACHE_ARCHIVE_URL' -o /tmp/graphhopper-cache.tgz; tar -xzf /tmp/graphhopper-cache.tgz -C /data; test -d /data/graph-cache" \
    < /dev/null
  write_remote_stage "cache_restore_done"
}

export_graphhopper_cache() {
  if [[ "$AWS_ROUTING_MODE" == "fake" ]]; then
    return 0
  fi
  if [[ "$GRAPHHOPPER_CACHE_EXPORT" != "true" ]]; then
    return 0
  fi
  echo "export_graphhopper_cache target=ops/loadtest/results/$TEST_ID-graphhopper-cache.tgz"
  docker compose --env-file .env.test $COMPOSE_FILES run -T --rm --no-deps --entrypoint sh graphhopper-prepare -c \
    "set -eu; test -d /data/graph-cache; tar -czf - -C /data graph-cache" \
    < /dev/null \
    > ops/loadtest/results/"$TEST_ID"-graphhopper-cache.tgz
}

restore_graphhopper_cache
write_remote_stage "compose_up_start"
docker compose --env-file .env.test $COMPOSE_FILES up --build -d
write_remote_stage "compose_up_done"

health_status() {
  local candidate status
  for candidate in 8080; do
    status="$(curl -sS -o /tmp/"$LABEL"-health.json -w '%{http_code}' --max-time 5 http://127.0.0.1:"$candidate"/health 2>/tmp/"$LABEL"-health.err || true)"
    if [[ "$status" == "200" ]]; then
      printf '%s\n' "$status"
      return 0
    fi
  done
  printf '%s\n' "${status:-000}"
  return 1
}

for attempt in $(seq 1 "$REMOTE_HEALTH_MAX_ATTEMPTS"); do
  if status="$(health_status)"; then
    break
  fi
  sleep 5
done
if [[ "${status:-}" != "200" ]]; then
  docker compose --env-file .env.test $COMPOSE_FILES ps || true
  docker compose --env-file .env.test $COMPOSE_FILES logs --tail=200 bike-back || true
  echo "health check did not pass after $REMOTE_HEALTH_MAX_ATTEMPTS attempts; last_status=${status:-000}" >&2
  exit 21
fi
echo "health_status=$status"
write_remote_stage "health_ready status=$status"

graphhopper_route_status() {
  docker compose --env-file .env.test $COMPOSE_FILES run -T --rm --no-deps --entrypoint sh graphhopper-prepare -c \
    "curl -sS -o /tmp/graphhopper-route-ready.json -w '%{http_code}' --max-time 10 'http://graphhopper:8989/route?profile=bike&point=37.481247,126.952739&point=37.551200,126.988200&points_encoded=false&elevation=true'" \
    < /dev/null \
    2>/tmp/"$LABEL"-graphhopper-ready.err || true
}

if [[ "$AWS_ROUTING_MODE" == "fake" ]]; then
  echo "graphhopper_route_ready_status=skipped_fake_routing"
  write_remote_stage "graphhopper_ready skipped_fake_routing"
else
  for attempt in $(seq 1 "$REMOTE_GRAPHHOPPER_READY_MAX_ATTEMPTS"); do
    graphhopper_status="$(graphhopper_route_status)"
    echo "graphhopper_attempt=$attempt status=${graphhopper_status:-000}"
    if [[ "$graphhopper_status" == "200" ]]; then
      break
    fi
    if [[ "$attempt" == "$REMOTE_GRAPHHOPPER_READY_MAX_ATTEMPTS" ]]; then
      docker compose --env-file .env.test $COMPOSE_FILES ps || true
      docker compose --env-file .env.test $COMPOSE_FILES logs --tail=200 graphhopper || true
      echo "GraphHopper route readiness did not pass after $REMOTE_GRAPHHOPPER_READY_MAX_ATTEMPTS attempts; last_status=${graphhopper_status:-000}" >&2
      exit 23
    fi
    sleep 5
  done
  if [[ "${graphhopper_status:-}" != "200" ]]; then
    docker compose --env-file .env.test $COMPOSE_FILES ps || true
    docker compose --env-file .env.test $COMPOSE_FILES logs --tail=200 graphhopper || true
    echo "GraphHopper route readiness did not pass after $REMOTE_GRAPHHOPPER_READY_MAX_ATTEMPTS attempts; last_status=${graphhopper_status:-000}" >&2
    exit 23
  fi
  echo "graphhopper_route_ready_status=$graphhopper_status"
  write_remote_stage "graphhopper_ready status=$graphhopper_status"
fi

mkdir -p ops/loadtest/results
set +e
echo "starting_k6 test_id=$TEST_ID duration=$RUN_DURATION"
write_remote_stage "k6_start"
k6 run --quiet \
  -e BASE_URL=http://127.0.0.1:8080 \
  -e TEST_ID="$TEST_ID" \
  -e SUMMARY_PATH=ops/loadtest/results/"$TEST_ID"-summary.json \
  -e AI_ROUTE_VUS="$K6_AI_ROUTE_VUS" \
  -e COURSE_MAP_READ_VUS="$K6_COURSE_MAP_READ_VUS" \
  -e FREE_RIDE_VUS="$K6_FREE_RIDE_VUS" \
  -e COURSE_FOLLOW_VUS="$K6_COURSE_FOLLOW_VUS" \
  -e RIDE_FINALIZATION_VUS="$K6_RIDE_FINALIZATION_VUS" \
  -e RUN_DURATION="$RUN_DURATION" \
  -e SLEEP_SECONDS=1 \
  -e COURSE_READY_MAX_ATTEMPTS="$K6_COURSE_READY_MAX_ATTEMPTS" \
  -e COURSE_READY_POLL_SECONDS="$K6_COURSE_READY_POLL_SECONDS" \
  -e RIDE_FINALIZATION_REQUIRE_READY="$K6_RIDE_FINALIZATION_REQUIRE_READY" \
  -e RIDE_FINALIZATION_READY_FAILURE_THRESHOLD="$K6_RIDE_FINALIZATION_READY_FAILURE_THRESHOLD" \
  ops/loadtest/k6/ai-route-graphhopper-100-users.js | tee ops/loadtest/results/"$TEST_ID"-k6.log
k6_status="${PIPESTATUS[0]}"
set -e
echo "$k6_status" > ops/loadtest/results/"$TEST_ID"-k6-exit-code.txt
echo "k6_status=$k6_status"
write_remote_stage "k6_done status=$k6_status"

docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}' \
  bike-test-backend bike-test-postgres bike-test-graphhopper bike-test-ai-route-worker \
  > ops/loadtest/results/"$TEST_ID"-docker-stats.txt || true
docker compose --env-file .env.test $COMPOSE_FILES logs --since=20m bike-back \
  | grep -E 'duplicate key|UnexpectedRollbackException|ride_record_finalization_failed|ERROR' \
  > ops/loadtest/results/"$TEST_ID"-error-scan.txt || true
docker compose --env-file .env.test $COMPOSE_FILES logs --since=20m ai-route-worker graphhopper \
  > ops/loadtest/results/"$TEST_ID"-routing-logs.txt || true
docker compose --env-file .env.test $COMPOSE_FILES ps \
  > ops/loadtest/results/"$TEST_ID"-compose-ps.txt || true
export_graphhopper_cache
if [[ "$RESET_GRAPHHOPPER_CACHE" == "true" ]]; then
  docker compose --env-file .env.test $COMPOSE_FILES down -v
else
  docker compose --env-file .env.test $COMPOSE_FILES down
fi
rm -f .env.test
REMOTE
  remote_status=$?
  set -e

  scp_from_instance_optional "$remote_dir/dev/bike-back/ops/loadtest/results/$test_id-remote-exit-code.txt" \
    "$EVIDENCE_DIR/$test_id-remote-exit-code.txt"
  scp_from_instance_optional "$remote_dir/dev/bike-back/ops/loadtest/results/$test_id-remote-stage.txt" \
    "$EVIDENCE_DIR/$test_id-remote-stage.txt"
  scp_from_instance_optional "$remote_dir/dev/bike-back/ops/loadtest/results/$test_id-summary.json" \
    "$EVIDENCE_DIR/$test_id-summary.json"
  scp_from_instance_optional "$remote_dir/dev/bike-back/ops/loadtest/results/$test_id-k6.log" \
    "$EVIDENCE_DIR/$test_id-k6.log"
  scp_from_instance_optional "$remote_dir/dev/bike-back/ops/loadtest/results/$test_id-k6-exit-code.txt" \
    "$EVIDENCE_DIR/$test_id-k6-exit-code.txt"
  scp_from_instance_optional "$remote_dir/dev/bike-back/ops/loadtest/results/$test_id-docker-stats.txt" \
    "$EVIDENCE_DIR/$test_id-docker-stats.txt"
  scp_from_instance_optional "$remote_dir/dev/bike-back/ops/loadtest/results/$test_id-error-scan.txt" \
    "$EVIDENCE_DIR/$test_id-error-scan.txt"
  scp_from_instance_optional "$remote_dir/dev/bike-back/ops/loadtest/results/$test_id-routing-logs.txt" \
    "$EVIDENCE_DIR/$test_id-routing-logs.txt"
  scp_from_instance_optional "$remote_dir/dev/bike-back/ops/loadtest/results/$test_id-compose-ps.txt" \
    "$EVIDENCE_DIR/$test_id-compose-ps.txt"
  scp_from_instance_optional "$remote_dir/dev/bike-back/ops/loadtest/results/$test_id-bike-back-tail.log" \
    "$EVIDENCE_DIR/$test_id-bike-back-tail.log"
  scp_from_instance_optional "$remote_dir/dev/bike-back/ops/loadtest/results/$test_id-health.json" \
    "$EVIDENCE_DIR/$test_id-health.json"
  scp_from_instance_optional "$remote_dir/dev/bike-back/ops/loadtest/results/$test_id-health.err" \
    "$EVIDENCE_DIR/$test_id-health.err"
  scp_from_instance_optional "$remote_dir/dev/bike-back/ops/loadtest/results/$test_id-graphhopper-ready.err" \
    "$EVIDENCE_DIR/$test_id-graphhopper-ready.err"
  scp_from_instance_optional "$remote_dir/dev/bike-back/ops/loadtest/results/$test_id-graphhopper-cache.tgz" \
    "$EVIDENCE_DIR/$test_id-graphhopper-cache.tgz"

  if [[ "$remote_status" != "0" ]]; then
    echo "$label remote run failed with exit code $remote_status" >&2
    exit "$remote_status"
  fi

  if [[ ! -f "$EVIDENCE_DIR/$test_id-k6-exit-code.txt" ]]; then
    echo "$label remote run ended without k6 exit code; evidence: $EVIDENCE_DIR" >&2
    exit 24
  fi

  local k6_exit_code
  k6_exit_code="$(tr -d '[:space:]' < "$EVIDENCE_DIR/$test_id-k6-exit-code.txt")"
  if [[ "$label" == "after" && "$k6_exit_code" != "0" && "$ALLOW_AFTER_K6_FAILURE" != "true" ]]; then
    echo "after k6 failed with exit code $k6_exit_code" >&2
    exit 22
  fi
}

main() {
  require_command aws
  require_command git
  require_command rsync
  require_command ssh
  require_command scp
  require_command tar
  validate_cost_guardrails

  if [[ ! -d "$AI_ROUTE_DIR" ]]; then
    echo "AI route worker directory not found: $AI_ROUTE_DIR" >&2
    exit 11
  fi
  if [[ -n "$SECRET_ENV_FILE" && ! -f "$SECRET_ENV_FILE" ]]; then
    echo "SECRET_ENV_FILE does not exist: $SECRET_ENV_FILE" >&2
    exit 13
  fi

  mkdir -p "$EVIDENCE_DIR"
  write_preflight_receipt
  if [[ "$VALIDATE_ONLY" == "true" ]]; then
    log "preflight validation complete. Evidence: $EVIDENCE_DIR/C000-aws-preflight.json"
    return 0
  fi

  TMP_DIR="$(mktemp -d)"

  local vpc_id subnet_id ami_id my_cidr expires_at user_data_file
  vpc_id="$(aws ec2 describe-vpcs --region "$AWS_REGION" --filters Name=is-default,Values=true --query 'Vpcs[0].VpcId' --output text)"
  subnet_id="$(aws ec2 describe-subnets --region "$AWS_REGION" --filters Name=default-for-az,Values=true --query 'Subnets[0].SubnetId' --output text)"
  ami_id="$(aws ec2 describe-images --region "$AWS_REGION" --owners 099720109477 --filters 'Name=name,Values=ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*' 'Name=state,Values=available' --query 'sort_by(Images,&CreationDate)[-1].ImageId' --output text)"
  my_cidr="$(curl -fsS https://checkip.amazonaws.com 2>/dev/null | tr -d '[:space:]')/32"
  expires_at="$(date -u -d "+$INSTANCE_TTL_SECONDS seconds" +%Y-%m-%dT%H:%M:%SZ)"
  user_data_file="$TMP_DIR/user-data.sh"
  cat > "$user_data_file" <<EOF
#!/usr/bin/env bash
set -euo pipefail
nohup bash -c 'sleep $INSTANCE_TTL_SECONDS; /sbin/shutdown -h now' >/var/log/bike-loadtest-ttl-shutdown.log 2>&1 &
EOF

  KEY_NAME="$PREFIX-key"
  KEY_FILE="$TMP_DIR/$KEY_NAME.pem"
  aws ec2 create-key-pair --region "$AWS_REGION" --key-name "$KEY_NAME" --query 'KeyMaterial' --output text > "$KEY_FILE"
  chmod 600 "$KEY_FILE"

  SG_ID="$(aws ec2 create-security-group --region "$AWS_REGION" --group-name "$PREFIX-sg" --description "$PREFIX temporary k6 load test" --vpc-id "$vpc_id" --query 'GroupId' --output text)"
  aws ec2 authorize-security-group-ingress --region "$AWS_REGION" --group-id "$SG_ID" --protocol tcp --port 22 --cidr "$my_cidr" >/dev/null

  INSTANCE_ID="$(aws ec2 run-instances \
    --region "$AWS_REGION" \
    --image-id "$ami_id" \
    --instance-type "$INSTANCE_TYPE" \
    --key-name "$KEY_NAME" \
    --security-group-ids "$SG_ID" \
    --subnet-id "$subnet_id" \
    --instance-initiated-shutdown-behavior terminate \
    --user-data "file://$user_data_file" \
    --block-device-mappings "DeviceName=/dev/sda1,Ebs={VolumeSize=$ROOT_VOLUME_SIZE_GB,VolumeType=gp3,DeleteOnTermination=true}" \
    --tag-specifications \
      "ResourceType=instance,Tags=[{Key=Name,Value=$PREFIX},{Key=Purpose,Value=bike-course-follow-k6},{Key=AutoDelete,Value=true},{Key=ExpiresAt,Value=$expires_at}]" \
      "ResourceType=volume,Tags=[{Key=Name,Value=$PREFIX-root},{Key=Purpose,Value=bike-course-follow-k6},{Key=AutoDelete,Value=true},{Key=ExpiresAt,Value=$expires_at}]" \
    --query 'Instances[0].InstanceId' \
    --output text)"

  log "waiting for EC2 $INSTANCE_ID"
  aws ec2 wait instance-running --region "$AWS_REGION" --instance-ids "$INSTANCE_ID"
  aws ec2 wait instance-status-ok --region "$AWS_REGION" --instance-ids "$INSTANCE_ID"
  INSTANCE_PUBLIC_IP="$(aws ec2 describe-instances --region "$AWS_REGION" --instance-ids "$INSTANCE_ID" --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)"

  for attempt in $(seq 1 "$SSH_READY_MAX_ATTEMPTS"); do
    if ssh_run 'true' >/dev/null 2>&1; then
      break
    fi
    if [[ "$attempt" == "$SSH_READY_MAX_ATTEMPTS" ]]; then
      echo "SSH did not become ready after $SSH_READY_MAX_ATTEMPTS attempts" >&2
      exit 20
    fi
    sleep 5
  done

	  if [[ -n "$SECRET_ENV_FILE" ]]; then
	    REMOTE_SECRET_ENV="/home/$SSH_USER/$PREFIX-secrets.env"
	    log "uploading secret env file for runtime use"
	    scp_to_instance "$SECRET_ENV_FILE" "$REMOTE_SECRET_ENV"
	    ssh_run "chmod 600 '$REMOTE_SECRET_ENV'"
	  fi
  if [[ -n "$GRAPHHOPPER_CACHE_ARCHIVE_FILE" ]]; then
    REMOTE_GRAPHHOPPER_CACHE_ARCHIVE="/home/$SSH_USER/$PREFIX-graphhopper-cache.tgz"
    log "uploading graphhopper cache archive for runtime restore"
    scp_to_instance "$GRAPHHOPPER_CACHE_ARCHIVE_FILE" "$REMOTE_GRAPHHOPPER_CACHE_ARCHIVE"
    ssh_run "chmod 600 '$REMOTE_GRAPHHOPPER_CACHE_ARCHIVE'"
  fi

  log "installing remote dependencies"
  ssh_run "K6_VERSION='$K6_VERSION' bash -s" <<'REMOTE'
set -Eeuo pipefail
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg unzip jq rsync openjdk-17-jdk-headless
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sudo sh
fi
sudo usermod -aG docker "$USER" || true
sudo systemctl enable --now docker
if ! command -v k6 >/dev/null 2>&1; then
  arch="$(uname -m)"
  case "$arch" in
    x86_64) k6_arch=amd64 ;;
    aarch64|arm64) k6_arch=arm64 ;;
    *) echo "unsupported arch: $arch" >&2; exit 12 ;;
  esac
  tmp="$(mktemp -d)"
  curl -fsSL "https://github.com/grafana/k6/releases/download/${K6_VERSION}/k6-${K6_VERSION}-linux-${k6_arch}.tar.gz" \
    | tar -xz -C "$tmp" --strip-components=1
  sudo mv "$tmp/k6" /usr/local/bin/k6
  rm -rf "$tmp"
fi
docker --version
docker compose version
k6 version
REMOTE

  local before_tar="" after_tar
  after_tar="$(package_tree after "worktree")"

  if [[ "$RUN_BEFORE" == "true" ]]; then
    before_tar="$(package_tree before "git:$BEFORE_REF")"
    run_remote_case before "$before_tar"
  fi
  run_remote_case after "$after_tar"
  if [[ -n "$REMOTE_SECRET_ENV" ]]; then
    ssh_run "rm -f '$REMOTE_SECRET_ENV'" >/dev/null 2>&1 || true
  fi

  {
    echo "{"
    echo "  \"prefix\": \"$PREFIX\","
    echo "  \"region\": \"$AWS_REGION\","
    echo "  \"instanceType\": \"$INSTANCE_TYPE\","
    echo "  \"instanceId\": \"$INSTANCE_ID\","
    echo "  \"publicIp\": \"$INSTANCE_PUBLIC_IP\","
    echo "  \"beforeRef\": \"$BEFORE_REF\","
	    echo "  \"runDuration\": \"$RUN_DURATION\","
	    echo "  \"routingMode\": \"$AWS_ROUTING_MODE\","
	    echo "  \"graphhopperCacheArchiveUrlProvided\": $([[ -n "$GRAPHHOPPER_CACHE_ARCHIVE_URL" ]] && echo true || echo false),"
	    echo "  \"graphhopperCacheArchiveFileProvided\": $([[ -n "$GRAPHHOPPER_CACHE_ARCHIVE_FILE" ]] && echo true || echo false),"
	    echo "  \"instanceTtlSeconds\": $INSTANCE_TTL_SECONDS,"
	    echo "  \"expiresAt\": \"$expires_at\","
	    echo "  \"vus\": {\"aiRoute\": $K6_AI_ROUTE_VUS, \"courseMapRead\": $K6_COURSE_MAP_READ_VUS, \"freeRide\": $K6_FREE_RIDE_VUS, \"courseFollow\": $K6_COURSE_FOLLOW_VUS},"
    echo "  \"secretEnvProvided\": $([[ -n "$SECRET_ENV_FILE" ]] && echo true || echo false),"
    echo "  \"beforeSummary\": \"$PREFIX-before-summary.json\","
    echo "  \"afterSummary\": \"$PREFIX-after-summary.json\""
    echo "}"
  } > "$EVIDENCE_DIR/C002-aws-run-metadata.json"

  log "AWS compose+k6 run complete. Evidence: $EVIDENCE_DIR"
}

main "$@"

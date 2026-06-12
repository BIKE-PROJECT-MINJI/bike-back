#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PARENT_DIR="$(cd "$ROOT_DIR/.." && pwd)"
AI_ROUTE_DIR="${AI_ROUTE_DIR:-$PARENT_DIR/bike-ai-route}"

AWS_REGION="${AWS_REGION:-$(aws configure get region 2>/dev/null || true)}"
AWS_REGION="${AWS_REGION:-ap-northeast-2}"
PREFIX="${PREFIX:-bike-ulw-loadtest-$(date +%Y%m%d-%H%M%S)}"
INSTANCE_TYPE="${INSTANCE_TYPE:-t3.xlarge}"
ROOT_VOLUME_SIZE_GB="${ROOT_VOLUME_SIZE_GB:-50}"
BEFORE_REF="${BEFORE_REF:-origin/main}"
RUN_DURATION="${RUN_DURATION:-2m}"
EVIDENCE_DIR="${EVIDENCE_DIR:-$ROOT_DIR/ops/loadtest/results/$PREFIX}"
SSH_USER="${SSH_USER:-ubuntu}"
K6_VERSION="${K6_VERSION:-v1.4.1}"
SECRET_ENV_FILE="${SECRET_ENV_FILE:-}"
K6_AI_ROUTE_VUS="${K6_AI_ROUTE_VUS:-0}"
K6_COURSE_MAP_READ_VUS="${K6_COURSE_MAP_READ_VUS:-0}"
K6_FREE_RIDE_VUS="${K6_FREE_RIDE_VUS:-50}"
K6_COURSE_FOLLOW_VUS="${K6_COURSE_FOLLOW_VUS:-50}"
RUN_BEFORE="${RUN_BEFORE:-true}"
RESET_GRAPHHOPPER_CACHE="${RESET_GRAPHHOPPER_CACHE:-false}"
SSH_READY_MAX_ATTEMPTS="${SSH_READY_MAX_ATTEMPTS:-60}"
SSH_CONNECT_TIMEOUT_SECONDS="${SSH_CONNECT_TIMEOUT_SECONDS:-10}"
REMOTE_HEALTH_MAX_ATTEMPTS="${REMOTE_HEALTH_MAX_ATTEMPTS:-120}"
REMOTE_GRAPHHOPPER_READY_MAX_ATTEMPTS="${REMOTE_GRAPHHOPPER_READY_MAX_ATTEMPTS:-180}"
INSTANCE_TTL_SECONDS="${INSTANCE_TTL_SECONDS:-14400}"
ALLOW_AFTER_K6_FAILURE="${ALLOW_AFTER_K6_FAILURE:-false}"

TMP_DIR=""
KEY_NAME=""
KEY_FILE=""
SG_ID=""
INSTANCE_ID=""
INSTANCE_PUBLIC_IP=""
REMOTE_SECRET_ENV=""
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
    rsync -a --delete \
      --exclude .git \
      --exclude .gradle \
      --include '.env.example' \
      --include '.env.test.example' \
      --exclude '.env' \
      --exclude '.env.*' \
      --exclude build \
      --exclude 'ops/loadtest/results' \
      "$ROOT_DIR/" "$package_dir/dev/bike-back/"
  fi

  rsync -a --delete \
    --exclude .git \
    --include '.env.example' \
    --exclude '.env' \
    --exclude '.env.*' \
    --exclude .venv \
    --exclude .pytest_cache \
    --exclude '__pycache__' \
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

run_remote_case() {
  local label="$1"
  local tarball="$2"
  local remote_tar="/home/$SSH_USER/$label.tgz"
  local remote_dir="/home/$SSH_USER/bike-loadtest/$label"
  local test_id="$PREFIX-$label"

  log "uploading $label bundle"
  scp_to_instance "$tarball" "$remote_tar"

  log "running $label compose+k6"
  ssh_run "LABEL='$label' REMOTE_TAR='$remote_tar' REMOTE_DIR='$remote_dir' TEST_ID='$test_id' RUN_DURATION='$RUN_DURATION' REMOTE_SECRET_ENV='$REMOTE_SECRET_ENV' K6_AI_ROUTE_VUS='$K6_AI_ROUTE_VUS' K6_COURSE_MAP_READ_VUS='$K6_COURSE_MAP_READ_VUS' K6_FREE_RIDE_VUS='$K6_FREE_RIDE_VUS' K6_COURSE_FOLLOW_VUS='$K6_COURSE_FOLLOW_VUS' REMOTE_HEALTH_MAX_ATTEMPTS='$REMOTE_HEALTH_MAX_ATTEMPTS' REMOTE_GRAPHHOPPER_READY_MAX_ATTEMPTS='$REMOTE_GRAPHHOPPER_READY_MAX_ATTEMPTS' RESET_GRAPHHOPPER_CACHE='$RESET_GRAPHHOPPER_CACHE' bash -s" <<'REMOTE'
	set -Eeuo pipefail
	mkdir -p "$REMOTE_DIR"
	tar -xzf "$REMOTE_TAR" -C "$REMOTE_DIR"
	cd "$REMOTE_DIR/dev/bike-back"
	chmod +x ./gradlew
	cp .env.test.example .env.test
	chmod 600 .env.test
	remote_cleanup() {
	  if [[ "$RESET_GRAPHHOPPER_CACHE" == "true" ]]; then
	    docker compose --env-file .env.test -f docker-compose.test.yml down -v >/dev/null 2>&1 || true
	  else
	    docker compose --env-file .env.test -f docker-compose.test.yml down >/dev/null 2>&1 || true
	  fi
	  rm -f .env.test "$REMOTE_SECRET_ENV"
	}
	trap remote_cleanup EXIT
	if [[ -n "$REMOTE_SECRET_ENV" && -f "$REMOTE_SECRET_ENV" ]]; then
	  grep -E '^(GEMINI_API_KEY|GEMINI_MODEL|GOOGLE_MODEL|GOOGLE_API_KEY|OPENAI_API_KEY|OPENAI_MODEL|GRAPHHOPPER_API_KEY|KAKAO_LOCAL_REST_API_KEY|KAKAO_MOBILITY_REST_API_KEY)=' "$REMOTE_SECRET_ENV" >> .env.test || true
	  chmod 600 "$REMOTE_SECRET_ENV" .env.test
	fi
./gradlew --no-daemon bootJar --console=plain
if [[ "$RESET_GRAPHHOPPER_CACHE" == "true" ]]; then
  docker compose --env-file .env.test -f docker-compose.test.yml down -v >/dev/null 2>&1 || true
else
  docker compose --env-file .env.test -f docker-compose.test.yml down >/dev/null 2>&1 || true
fi
docker compose --env-file .env.test -f docker-compose.test.yml up --build -d

health_status() {
  local candidate status
  for candidate in 8081 18081; do
    status="$(curl -sS -o /tmp/"$LABEL"-health.json -w '%{http_code}' --max-time 5 http://127.0.0.1:"$candidate"/actuator/health 2>/tmp/"$LABEL"-health.err || true)"
    if [[ "$status" == "200" || "$status" == "401" ]]; then
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
if [[ "${status:-}" != "200" && "${status:-}" != "401" ]]; then
  docker compose --env-file .env.test -f docker-compose.test.yml ps || true
  docker compose --env-file .env.test -f docker-compose.test.yml logs --tail=200 bike-back || true
  echo "health check did not pass after $REMOTE_HEALTH_MAX_ATTEMPTS attempts; last_status=${status:-000}" >&2
  exit 21
fi
echo "health_status=$status"

graphhopper_route_status() {
  docker compose --env-file .env.test -f docker-compose.test.yml run --rm --no-deps --entrypoint sh graphhopper-prepare -c \
    "curl -sS -o /tmp/graphhopper-route-ready.json -w '%{http_code}' --max-time 10 'http://graphhopper:8989/route?profile=bike&point=37.481247,126.952739&point=37.551200,126.988200&points_encoded=false&elevation=true'" \
    2>/tmp/"$LABEL"-graphhopper-ready.err || true
}

for attempt in $(seq 1 "$REMOTE_GRAPHHOPPER_READY_MAX_ATTEMPTS"); do
  graphhopper_status="$(graphhopper_route_status)"
  if [[ "$graphhopper_status" == "200" ]]; then
    break
  fi
  sleep 5
done
if [[ "${graphhopper_status:-}" != "200" ]]; then
  docker compose --env-file .env.test -f docker-compose.test.yml ps || true
  docker compose --env-file .env.test -f docker-compose.test.yml logs --tail=200 graphhopper || true
  echo "GraphHopper route readiness did not pass after $REMOTE_GRAPHHOPPER_READY_MAX_ATTEMPTS attempts; last_status=${graphhopper_status:-000}" >&2
  exit 23
fi
echo "graphhopper_route_ready_status=$graphhopper_status"

mkdir -p ops/loadtest/results
set +e
k6 run --quiet \
  -e BASE_URL=http://127.0.0.1:8080 \
  -e TEST_ID="$TEST_ID" \
  -e SUMMARY_PATH=ops/loadtest/results/"$TEST_ID"-summary.json \
  -e AI_ROUTE_VUS="$K6_AI_ROUTE_VUS" \
  -e COURSE_MAP_READ_VUS="$K6_COURSE_MAP_READ_VUS" \
  -e FREE_RIDE_VUS="$K6_FREE_RIDE_VUS" \
  -e COURSE_FOLLOW_VUS="$K6_COURSE_FOLLOW_VUS" \
  -e RUN_DURATION="$RUN_DURATION" \
  -e SLEEP_SECONDS=1 \
  -e COURSE_READY_MAX_ATTEMPTS=20 \
  -e COURSE_READY_POLL_SECONDS=0.1 \
  ops/loadtest/k6/ai-route-graphhopper-100-users.js | tee ops/loadtest/results/"$TEST_ID"-k6.log
k6_status="${PIPESTATUS[0]}"
set -e
echo "$k6_status" > ops/loadtest/results/"$TEST_ID"-k6-exit-code.txt

docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}' \
  bike-test-backend bike-test-postgres bike-test-graphhopper bike-test-ai-route-worker \
  > ops/loadtest/results/"$TEST_ID"-docker-stats.txt || true
docker compose --env-file .env.test -f docker-compose.test.yml logs --since=20m bike-back \
  | grep -E 'duplicate key|UnexpectedRollbackException|ride_record_finalization_failed|ERROR' \
  > ops/loadtest/results/"$TEST_ID"-error-scan.txt || true
docker compose --env-file .env.test -f docker-compose.test.yml logs --since=20m ai-route-worker graphhopper \
  > ops/loadtest/results/"$TEST_ID"-routing-logs.txt || true
docker compose --env-file .env.test -f docker-compose.test.yml ps \
  > ops/loadtest/results/"$TEST_ID"-compose-ps.txt || true
if [[ "$RESET_GRAPHHOPPER_CACHE" == "true" ]]; then
  docker compose --env-file .env.test -f docker-compose.test.yml down -v
else
  docker compose --env-file .env.test -f docker-compose.test.yml down
fi
rm -f .env.test
REMOTE

  scp_from_instance "$remote_dir/dev/bike-back/ops/loadtest/results/$test_id-summary.json" \
    "$EVIDENCE_DIR/$test_id-summary.json"
  scp_from_instance "$remote_dir/dev/bike-back/ops/loadtest/results/$test_id-k6.log" \
  "$EVIDENCE_DIR/$test_id-k6.log"
  scp_from_instance "$remote_dir/dev/bike-back/ops/loadtest/results/$test_id-k6-exit-code.txt" \
    "$EVIDENCE_DIR/$test_id-k6-exit-code.txt"
  scp_from_instance "$remote_dir/dev/bike-back/ops/loadtest/results/$test_id-docker-stats.txt" \
    "$EVIDENCE_DIR/$test_id-docker-stats.txt"
  scp_from_instance "$remote_dir/dev/bike-back/ops/loadtest/results/$test_id-error-scan.txt" \
    "$EVIDENCE_DIR/$test_id-error-scan.txt"
  scp_from_instance "$remote_dir/dev/bike-back/ops/loadtest/results/$test_id-routing-logs.txt" \
    "$EVIDENCE_DIR/$test_id-routing-logs.txt"
  scp_from_instance "$remote_dir/dev/bike-back/ops/loadtest/results/$test_id-compose-ps.txt" \
    "$EVIDENCE_DIR/$test_id-compose-ps.txt"

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

  if [[ ! -d "$AI_ROUTE_DIR" ]]; then
    echo "AI route worker directory not found: $AI_ROUTE_DIR" >&2
    exit 11
  fi
  if [[ -n "$SECRET_ENV_FILE" && ! -f "$SECRET_ENV_FILE" ]]; then
    echo "SECRET_ENV_FILE does not exist: $SECRET_ENV_FILE" >&2
    exit 13
  fi

  mkdir -p "$EVIDENCE_DIR"
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

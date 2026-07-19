#!/usr/bin/env bash
set -euo pipefail

readonly ALLOW_IAM_CHANGE="${ALLOW_IAM_CHANGE:-NO}"
[[ "$ALLOW_IAM_CHANGE" == "YES" ]] || {
  printf 'set ALLOW_IAM_CHANGE=YES after reviewing operator-policy.json\n' >&2
  exit 1
}

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly STACK_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly USER_NAME="${AWS_IAM_USER_NAME:-JIMINSOO}"
readonly ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
readonly POLICY_NAME='gaja-ephemeral-validation-operator'
readonly POLICY_ARN="arn:aws:iam::${ACCOUNT_ID}:policy/${POLICY_NAME}"

if aws iam get-policy --policy-arn "$POLICY_ARN" >/dev/null 2>&1; then
  new_version="$(aws iam create-policy-version \
    --policy-arn "$POLICY_ARN" \
    --policy-document "file://$STACK_DIR/operator-policy.json" \
    --set-as-default \
    --query 'PolicyVersion.VersionId' \
    --output text)"
  while read -r old_version; do
    [[ -z "$old_version" || "$old_version" == "$new_version" ]] && continue
    aws iam delete-policy-version \
      --policy-arn "$POLICY_ARN" \
      --version-id "$old_version"
  done < <(aws iam list-policy-versions \
    --policy-arn "$POLICY_ARN" \
    --query 'Versions[?IsDefaultVersion==`false`].VersionId' \
    --output text | tr '\t' '\n')
else
  aws iam create-policy \
    --policy-name "$POLICY_NAME" \
    --policy-document "file://$STACK_DIR/operator-policy.json" \
    --description 'Supplemental operator permissions for disposable GAJA validation runs' \
    >/dev/null
fi

aws iam attach-user-policy \
  --user-name "$USER_NAME" \
  --policy-arn "$POLICY_ARN"

printf 'operator policy installed user=%s policy=%s\n' "$USER_NAME" "$POLICY_ARN"

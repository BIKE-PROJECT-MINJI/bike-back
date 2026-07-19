#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///

from __future__ import annotations

import json
import subprocess
import sys
from dataclasses import dataclass
from typing import Final, TextIO, cast

ROLES: Final[tuple[str, ...]] = (
    "app",
    "db",
    "redis",
    "graphhopper",
    "load",
    "observability",
)
ROLE_FILES: Final[dict[str, tuple[str, ...]]] = {
    "app": ("backend.env",),
    "graphhopper": ("graphhopper-assets.tar.gz",),
    "load": ("k6-scenarios.tar.gz",),
}
SECRET_NAMES: Final[tuple[str, ...]] = (
    "db-password",
    "redis-password",
    "jwt-secret",
    "grafana-password",
)
QUERY_FIELDS: Final[tuple[str, ...]] = (
    "aws_region",
    "artifact_bucket",
    "artifact_prefix",
    "secret_prefix",
    "schedule_name",
    "cleanup_at",
)


@dataclass(frozen=True, slots=True)
class GateQuery:
    aws_region: str
    artifact_bucket: str
    artifact_prefix: str
    secret_prefix: str
    schedule_name: str
    cleanup_at: str

    @classmethod
    def read(cls, source: TextIO) -> GateQuery:
        raw_payload = cast(object, json.loads(source.read()))
        if not isinstance(raw_payload, dict):
            raise ValueError("Terraform external query must be an object")
        payload = cast(dict[object, object], raw_payload)
        values: dict[str, str] = {}
        for field in QUERY_FIELDS:
            value = payload.get(field)
            if not isinstance(value, str) or not value:
                raise ValueError(f"missing string query field: {field}")
            values[field] = value
        return cls(**values)


def run_aws(*arguments: str) -> str:
    completed = subprocess.run(
        ["aws", *arguments],
        check=True,
        capture_output=True,
        text=True,
    )
    return completed.stdout.strip()


def verify_schedule(query: GateQuery) -> None:
    state = run_aws(
        "scheduler",
        "get-schedule",
        "--region",
        query.aws_region,
        "--name",
        query.schedule_name,
        "--query",
        "State",
        "--output",
        "text",
    )
    expression = run_aws(
        "scheduler",
        "get-schedule",
        "--region",
        query.aws_region,
        "--name",
        query.schedule_name,
        "--query",
        "ScheduleExpression",
        "--output",
        "text",
    )
    if state != "ENABLED" or expression != f"at({query.cleanup_at})":
        raise RuntimeError(
            f"cleanup schedule mismatch: state={state} expression={expression}"
        )


def verify_artifacts(query: GateQuery) -> None:
    common_files = (
        "bootstrap.sh",
        "bootstrap.sh.sha256",
        "common.sh",
        "images.tar.gz",
        "role.env",
        "SHA256SUMS",
    )
    for role in ROLES:
        for filename in (*common_files, *ROLE_FILES.get(role, ())):
            _ = run_aws(
                "s3api",
                "head-object",
                "--region",
                query.aws_region,
                "--bucket",
                query.artifact_bucket,
                "--key",
                f"{query.artifact_prefix}/{role}/{filename}",
            )


def verify_parameters(query: GateQuery) -> None:
    for secret_name in SECRET_NAMES:
        _ = run_aws(
            "ssm",
            "get-parameter",
            "--region",
            query.aws_region,
            "--name",
            f"{query.secret_prefix}{secret_name}",
            "--query",
            "Parameter.Name",
            "--output",
            "text",
        )


def main() -> None:
    query = GateQuery.read(sys.stdin)
    verify_schedule(query)
    verify_artifacts(query)
    verify_parameters(query)
    _ = json.dump({"ready": "true", "run_id": query.schedule_name}, sys.stdout)
    _ = sys.stdout.write("\n")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"bootstrap prerequisite gate failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error

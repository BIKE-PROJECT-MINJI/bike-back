#!/usr/bin/env python3
"""주행 기록 finalization smoke evidence 헬퍼."""

from __future__ import annotations

import json
import os
import time
from collections.abc import Callable
from typing import Any

from deployment_smoke_support import SmokeFailure
from deployment_smoke_support import api_data
from deployment_smoke_support import request
from deployment_smoke_support import verify_api_response


FINALIZATION_TIMEOUT_SEC = float(os.environ.get("BIKE_SMOKE_FINALIZATION_TIMEOUT_SEC", "30"))
FINALIZATION_POLL_INTERVAL_SEC = float(os.environ.get("BIKE_SMOKE_FINALIZATION_POLL_INTERVAL_SEC", "1"))


def poll_finalization_ready(base_url: str, ride_record_id: int, auth_header: dict[str, str]) -> dict[str, Any]:
    attempts: list[dict[str, Any]] = []
    deadline = time.monotonic() + FINALIZATION_TIMEOUT_SEC
    last_data: dict[str, Any] | None = None
    fields = ["status", "rawPointCount", "processedPointCount", "finalizationAttempts", "errorMessage"]

    while time.monotonic() < deadline:
        result = request("GET", f"{base_url}/api/v1/ride-records/{ride_record_id}", headers=auth_header)
        verify_api_response(result, 200, "ride finalization status")
        data = api_data(result)
        last_data = data
        attempts.append(pick(data, fields))
        status = data.get("status")
        if status == "READY":
            return {"status": "READY", "attempts": attempts, "last": pick(data, fields)}
        if status == "FAILED":
            raise SmokeFailure(f"ride record finalization failed: {data.get('errorMessage')}")
        time.sleep(FINALIZATION_POLL_INTERVAL_SEC)

    raise SmokeFailure(f"ride record did not become READY before timeout; attempts={attempts}, last={last_data}")


def finalization_job_snapshot(sql_scalar: Callable[[str], str], ride_record_id: int) -> dict[str, Any]:
    job = sql_json_object(sql_scalar, f"""
        select coalesce((
            select jsonb_build_object(
                'status', status,
                'attemptCount', attempt_count,
                'maxAttempts', max_attempts,
                'nextRunAt', next_run_at,
                'locked', locked_by is not null,
                'lastErrorCode', last_error_code
            )
            from ride_finalization_jobs
            where ride_record_id = {ride_record_id}
        ), '{{}}'::jsonb)::text
    """)
    backlog = sql_json_object(sql_scalar, """
        select coalesce(jsonb_object_agg(status, total), '{}'::jsonb)::text
        from (
            select status, count(*) as total
            from ride_finalization_jobs
            group by status
        ) grouped
    """)
    return {"job": job, "backlogByStatus": backlog}


def pick(data: dict[str, Any], keys: list[str]) -> dict[str, Any]:
    return {key: data.get(key) for key in keys if key in data}


def sql_json_object(sql_scalar: Callable[[str], str], query: str) -> dict[str, Any]:
    value = sql_scalar(query)
    if not value:
        return {}
    parsed = json.loads(value)
    if not isinstance(parsed, dict):
        raise SmokeFailure(f"SQL JSON 결과가 object가 아닙니다: {query}")
    return parsed

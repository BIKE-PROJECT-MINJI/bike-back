#!/usr/bin/env python3
"""배포 후보 백엔드 핵심 smoke 실행기.

실행 전 백엔드, DB, Redis, 필요 시 GraphHopper 호환 mock을 직접 띄운 상태에서 사용한다.
민감정보는 결과 JSON에 기록하지 않고 성공 여부와 응답 핵심 필드만 남긴다.
"""

from __future__ import annotations

import base64
import datetime as dt
import hashlib
import hmac
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

from deployment_finalization_smoke import finalization_job_snapshot
from deployment_finalization_smoke import poll_finalization_ready
from deployment_smoke_support import SmokeFailure
from deployment_smoke_support import api_data
from deployment_smoke_support import request
from deployment_smoke_support import require_status
from deployment_smoke_support import sanitize_headers
from deployment_smoke_support import slim
from deployment_smoke_support import verify_api_response


BASE_URL = os.environ.get("BIKE_SMOKE_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
MANAGEMENT_BASE_URL = os.environ.get("BIKE_SMOKE_MANAGEMENT_BASE_URL", "http://127.0.0.1:18081").rstrip("/")
GRAPHHOPPER_CONTROL_URL = os.environ.get("BIKE_SMOKE_GRAPHHOPPER_CONTROL_URL", "").rstrip("/")
DB_CONTAINER = os.environ.get("BIKE_SMOKE_DB_CONTAINER", "bike-deploy-smoke-postgres")
DB_NAME = os.environ.get("BIKE_SMOKE_DB_NAME", "bike")
REDIS_CONTAINER = os.environ.get("BIKE_SMOKE_REDIS_CONTAINER", "bike-deploy-smoke-redis")
OUTPUT_PATH = Path(os.environ.get("BIKE_SMOKE_OUTPUT", "/tmp/bike-deploy-smoke/evidence.json"))
DEFAULT_CORS_ORIGINS = "http://127.0.0.1:8081,http://localhost:8081"
CORS_ORIGINS = [
    origin.strip()
    for origin in os.environ.get("BIKE_SMOKE_CORS_ORIGINS", DEFAULT_CORS_ORIGINS).split(",")
    if origin.strip()
]


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def ops_token() -> str | None:
    secret = os.environ.get("AUTH_JWT_SECRET", "")
    issuer = os.environ.get("AUTH_JWT_ISSUER", "bike-back")
    if not secret:
        return None
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "iss": issuer,
        "sub": "ops-smoke",
        "iat": now,
        "exp": now + 600,
        "tokenType": "access",
        "roles": ["OPS"],
    }
    signing_input = ".".join([
        b64url(json.dumps(header, separators=(",", ":")).encode()),
        b64url(json.dumps(payload, separators=(",", ":")).encode()),
    ])
    signature = hmac.new(secret.encode(), signing_input.encode(), hashlib.sha256).digest()
    return signing_input + "." + b64url(signature)


def sql_scalar(query: str) -> str:
    return subprocess.check_output(
        ["docker", "exec", DB_CONTAINER, "psql", "-U", "bike", "-d", DB_NAME, "-tAc", query],
        text=True,
    ).strip()


def redis_ping() -> str:
    return subprocess.check_output(["docker", "exec", REDIS_CONTAINER, "redis-cli", "ping"], text=True).strip()


def set_graphhopper_mode(mode: str) -> None:
    if not GRAPHHOPPER_CONTROL_URL:
        return
    request("GET", f"{GRAPHHOPPER_CONTROL_URL}/__mode/{mode}")


def graphhopper_hits() -> dict[str, Any] | None:
    if not GRAPHHOPPER_CONTROL_URL:
        return None
    return request("GET", f"{GRAPHHOPPER_CONTROL_URL}/__hits")


def main() -> int:
    evidence: dict[str, Any] = {}
    health = request("GET", f"{BASE_URL}/health")
    verify_api_response(health, 200, "health")
    evidence["health"] = slim(health)

    actuator_health = request("GET", f"{MANAGEMENT_BASE_URL}/actuator/health")
    evidence["actuatorHealthNoAuth"] = slim(actuator_health)

    monitor_no_auth = request("GET", f"{BASE_URL}/health/monitor")
    verify_api_response(monitor_no_auth, 401, "monitor no auth", data_object_required=False)
    evidence["monitorNoAuth"] = slim(monitor_no_auth)

    token = ops_token()
    if token:
        monitor_ops = request("GET", f"{BASE_URL}/health/monitor", headers={"Authorization": f"Bearer {token}"})
        verify_api_response(monitor_ops, 200, "monitor ops")
        evidence["monitorOps"] = slim(monitor_ops)
        prometheus = request("GET", f"{MANAGEMENT_BASE_URL}/actuator/prometheus", headers={"Authorization": f"Bearer {token}"})
        evidence["prometheusOps"] = {
            "status": prometheus["status"],
            "bodyBytes": prometheus["bodyBytes"],
            "routingFailureMetricPresent": "bike_routing_provider_failure_total" in str(prometheus.get("body")),
        }
    else:
        evidence["monitorOps"] = {"skipped": "AUTH_JWT_SECRET is not set for smoke-generated OPS token"}

    email = f"deploy-smoke-{int(time.time())}@example.com"
    password = "Password123!"
    register = request("POST", f"{BASE_URL}/api/v1/auth/register", {
        "email": email,
        "password": password,
        "displayName": "DeploySmoke",
    })
    verify_api_response(register, 200, "auth register")
    register_data = api_data(register)
    access_token = register_data.get("accessToken")
    if not isinstance(access_token, str) or not access_token:
        raise SmokeFailure("auth register 응답에 accessToken이 없습니다.")
    evidence["authRegister"] = {
        "status": register["status"],
        "bodyBytes": register["bodyBytes"],
        "hasAccessToken": True,
        "dataKeys": sorted(register_data.keys()),
        "userId": register_data.get("userId"),
        "displayName": register_data.get("displayName"),
    }

    login = request("POST", f"{BASE_URL}/api/v1/auth/login", {
        "email": email,
        "password": password,
    })
    verify_api_response(login, 200, "auth login")
    evidence["authLogin"] = {
        "status": login["status"],
        "bodyBytes": login["bodyBytes"],
        "headers": sanitize_headers(login["headers"]),
        "hasAccessToken": isinstance(api_data(login).get("accessToken"), str),
    }

    courses = request("GET", f"{BASE_URL}/api/v1/courses?limit=5")
    verify_api_response(courses, 200, "course list")
    evidence["courseList"] = slim(courses)

    set_graphhopper_mode("success")
    ai_success = request("POST", f"{BASE_URL}/api/v1/ai-routes/plan/from-text", {
        "lat": 37.4812,
        "lon": 126.9527,
        "text": "평지 위주로 강이 보이는 코스 추천",
    }, headers={"X-Guest-Device-Id": "deploy-smoke-ai-success"})
    verify_api_response(ai_success, 200, "AI route fallback success")
    ai_data = api_data(ai_success)
    evidence["aiRouteFallbackSuccess"] = {
        "status": ai_success["status"],
        "bodyBytes": ai_success["bodyBytes"],
        "headers": sanitize_headers(ai_success["headers"]),
        "statusField": ai_data.get("status"),
        "routePointCount": len(ai_data.get("routePoints") or []),
        "aiGenerated": ai_data.get("aiGenerated"),
        "routingMetadata": ai_data.get("routingMetadata"),
    }

    if GRAPHHOPPER_CONTROL_URL:
        set_graphhopper_mode("fail")
        ai_fail = request("POST", f"{BASE_URL}/api/v1/ai-routes/plan/from-text", {
            "lat": 37.4812,
            "lon": 126.9527,
            "text": "평지 위주로 강이 보이는 코스 추천",
        }, headers={"X-Guest-Device-Id": "deploy-smoke-ai-fail"})
        evidence["aiRouteFallbackFailure"] = slim(ai_fail)
        evidence["graphhopperMockHits"] = graphhopper_hits()
        if token:
            prometheus_after_routing = request(
                "GET",
                f"{MANAGEMENT_BASE_URL}/actuator/prometheus",
                headers={"Authorization": f"Bearer {token}"},
            )
            evidence["prometheusOpsAfterRouting"] = {
                "status": prometheus_after_routing["status"],
                "bodyBytes": prometheus_after_routing["bodyBytes"],
                "routingFailureMetricPresent": "bike_routing_provider_failure_total" in str(prometheus_after_routing.get("body")),
            }

    auth_header = {"Authorization": f"Bearer {access_token}"}
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    started_at = now.isoformat().replace("+00:00", "Z")
    ended_at = (now + dt.timedelta(minutes=12)).isoformat().replace("+00:00", "Z")
    summary = request("POST", f"{BASE_URL}/api/v1/ride-records/summary", {
        "clientRideId": f"deploy-smoke-{int(time.time())}",
        "startedAt": started_at,
        "endedAt": ended_at,
        "summary": {"distanceM": 3200, "durationSec": 720},
    }, headers=auth_header)
    verify_api_response(summary, 200, "ride summary")
    summary_data = api_data(summary)
    ride_record_id = summary_data.get("rideRecordId")
    if not isinstance(ride_record_id, int):
        raise SmokeFailure("ride summary 응답에 rideRecordId가 없습니다.")
    evidence["rideSummary"] = {"status": summary["status"], "bodyBytes": summary["bodyBytes"], "data": summary_data}

    trace = request("POST", f"{BASE_URL}/api/v1/ride-records/{ride_record_id}/trace", {
        "routePoints": [
            {"pointOrder": 1, "latitude": 37.4812, "longitude": 126.9527, "capturedAt": started_at, "accuracyM": 5, "speedMps": 3.2},
            {"pointOrder": 2, "latitude": 37.4824, "longitude": 126.9553, "capturedAt": ended_at, "accuracyM": 6, "speedMps": 3.4},
        ]
    }, headers=auth_header)
    verify_api_response(trace, 200, "ride trace")
    evidence["rideTrace"] = {"status": trace["status"], "bodyBytes": trace["bodyBytes"], "data": api_data(trace)}
    evidence["rideFinalizationPolling"] = poll_finalization_ready(BASE_URL, ride_record_id, auth_header)
    evidence["rideFinalizationJobsBeforeDelete"] = finalization_job_snapshot(sql_scalar, ride_record_id)

    delete = request("DELETE", f"{BASE_URL}/api/v1/ride-records/{ride_record_id}", headers=auth_header)
    require_status(delete, 204, "ride delete")
    evidence["rideDelete"] = {"status": delete["status"], "bodyBytes": delete["bodyBytes"], "body": delete["body"]}
    time.sleep(1)

    evidence["databaseCountsAfterDelete"] = {
        "flywaySuccessCount": sql_scalar("select count(*) from flyway_schema_history where success = true"),
        "ride_records": sql_scalar("select count(*) from ride_records"),
        "ride_record_points": sql_scalar("select count(*) from ride_record_points"),
        "ride_record_processed_points": sql_scalar("select count(*) from ride_record_processed_points"),
    }
    evidence["redis"] = {"ping": redis_ping()}

    cors_results = []
    for origin in CORS_ORIGINS + ["https://not-allowed.example.com"]:
        result = request("OPTIONS", f"{BASE_URL}/api/v1/ai-routes/plan/from-text", headers={
            "Origin": origin,
            "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "content-type,x-guest-device-id",
        })
        cors_results.append({
            "origin": origin,
            "status": result["status"],
            "headers": sanitize_headers(result["headers"]),
            "bodyBytes": result["bodyBytes"],
        })
    evidence["corsPreflight"] = cors_results

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(json.dumps(evidence, ensure_ascii=False, indent=2))
    print(json.dumps(summary_output(evidence), ensure_ascii=False, indent=2))
    return 0


def summary_output(evidence: dict[str, Any]) -> dict[str, Any]:
    return {
        "health": evidence["health"]["status"],
        "monitorOps": evidence.get("monitorOps", {}).get("status", "skipped"),
        "register": evidence["authRegister"]["status"],
        "login": evidence["authLogin"]["status"],
        "courseList": evidence["courseList"]["status"],
        "aiSuccess": evidence["aiRouteFallbackSuccess"]["status"],
        "aiFail": evidence.get("aiRouteFallbackFailure", {}).get("status", "skipped"),
        "summary": evidence["rideSummary"]["status"],
        "trace": evidence["rideTrace"]["status"],
        "finalization": evidence["rideFinalizationPolling"]["status"],
        "finalizationJob": evidence["rideFinalizationJobsBeforeDelete"],
        "delete": evidence["rideDelete"],
        "dbCounts": evidence["databaseCountsAfterDelete"],
        "cors": evidence["corsPreflight"],
        "output": str(OUTPUT_PATH),
    }


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SmokeFailure as exception:
        print(f"SMOKE_FAILED: {exception}", file=sys.stderr)
        raise SystemExit(1)

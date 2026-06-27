#!/usr/bin/env python3
"""BIKE 낮은 부하 동시성/정합성 검증 evidence runner.

민감한 access token은 evidence 파일에 저장하지 않는다.
실패가 있어도 가능한 시나리오를 계속 실행하고 마지막에 요약으로 보고한다.
"""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import json
import os
import socket
import ssl
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from uuid import uuid4


BASE_URL = os.environ.get("BIKE_CONCURRENCY_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
DB_CONTAINER = os.environ.get("BIKE_CONCURRENCY_DB_CONTAINER", "bike-back-postgres")
DB_NAME = os.environ.get("BIKE_CONCURRENCY_DB_NAME", "bike")
DB_USER = os.environ.get("BIKE_CONCURRENCY_DB_USER", "bike")
OUTPUT_PATH = Path(os.environ.get(
    "BIKE_CONCURRENCY_OUTPUT",
    "ops/smoke/results/concurrency_consistency.json",
))
REQUEST_COUNT = int(os.environ.get("BIKE_CONCURRENCY_REQUEST_COUNT", "10"))


class SmokeFailure(Exception):
    pass


@dataclass(frozen=True)
class User:
    user_id: int
    access_token: str


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def later_iso(seconds: int) -> str:
    return (datetime.now(timezone.utc).replace(microsecond=0) + timedelta(seconds=seconds)).isoformat().replace("+00:00", "Z")


def request(
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
        token: str | None = None,
        request_id: str | None = None,
        timeout_sec: float = 30.0,
) -> dict[str, Any]:
    headers = {"Accept": "application/json"}
    if request_id:
        headers["X-Request-Id"] = request_id
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload, ensure_ascii=False).encode()

    http_request = urllib.request.Request(
        BASE_URL + path,
        data=data,
        headers=headers,
        method=method,
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(http_request, timeout=timeout_sec) as response:
            body_bytes = response.read()
            return http_result(method, path, response.status, response.headers, body_bytes, started)
    except urllib.error.HTTPError as error:
        body_bytes = error.read()
        return http_result(method, path, error.code, error.headers, body_bytes, started)
    except urllib.error.URLError as error:
        return {
            "method": method,
            "path": path,
            "status": 0,
            "durationMs": round((time.perf_counter() - started) * 1000, 2),
            "error": str(error),
        }


def http_result(method: str, path: str, status: int, headers: Any, body_bytes: bytes, started: float) -> dict[str, Any]:
    return {
        "method": method,
        "path": path,
        "status": status,
        "durationMs": round((time.perf_counter() - started) * 1000, 2),
        "bodyBytes": len(body_bytes),
        "headers": sanitize_headers(dict(headers)),
        "body": parse_json(body_bytes.decode(errors="replace")),
    }


def parse_json(value: str) -> Any:
    if not value:
        return None
    try:
        return json.loads(value)
    except json.JSONDecodeError:
        return value


def sanitize_headers(headers: dict[str, str]) -> dict[str, str]:
    allowed = {"X-Request-Id", "X-Trace-Id", "Content-Type"}
    return {key: value for key, value in headers.items() if key in allowed}


def api_data(result: dict[str, Any]) -> dict[str, Any]:
    body = result.get("body")
    if not isinstance(body, dict) or not isinstance(body.get("data"), dict):
        raise SmokeFailure(f"{result.get('method')} {result.get('path')} 응답에 data object가 없습니다.")
    return body["data"]


def register_user(label: str) -> User:
    email = f"concurrency-{label}-{int(time.time())}-{uuid4().hex[:8]}@example.com"
    result = request("POST", "/api/v1/auth/register", {
        "email": email,
        "password": "Password123!",
        "displayName": f"Concurrency{label}",
    }, request_id=f"conc-register-{label}")
    require_status(result, 200, f"register {label}")
    data = api_data(result)
    token = data.get("accessToken")
    user_id = data.get("userId")
    if not isinstance(token, str) or not isinstance(user_id, int):
        raise SmokeFailure(f"register {label} 응답에 userId/accessToken이 없습니다.")
    return User(user_id=user_id, access_token=token)


def require_status(result: dict[str, Any], expected: int, label: str) -> None:
    if result.get("status") != expected:
        raise SmokeFailure(f"{label} status={result.get('status')}, expected={expected}, body={result.get('body')}")


def route_payload(client_ride_id: str) -> dict[str, Any]:
    started = datetime.now(timezone.utc).replace(microsecond=0)
    points = [
        (37.481247, 126.952739),
        (37.482090, 126.956101),
        (37.483518, 126.960721),
        (37.484912, 126.965133),
    ]
    route_points = []
    for index, (lat, lon) in enumerate(points, start=1):
        route_points.append({
            "pointOrder": index,
            "latitude": lat,
            "longitude": lon,
            "capturedAt": (started + timedelta(seconds=index * 30)).isoformat().replace("+00:00", "Z"),
            "accuracyM": 5,
            "speedMps": 4.5,
        })
    ended = started + timedelta(seconds=len(points) * 30)
    return {
        "clientRideId": client_ride_id,
        "startedAt": started.isoformat().replace("+00:00", "Z"),
        "endedAt": ended.isoformat().replace("+00:00", "Z"),
        "summary": {
            "distanceM": 1200,
            "durationSec": 120,
        },
        "routePoints": route_points,
    }


def run_parallel(label: str, count: int, fn: Any) -> list[dict[str, Any]]:
    with concurrent.futures.ThreadPoolExecutor(max_workers=count, thread_name_prefix=label) as executor:
        futures = [executor.submit(fn, index) for index in range(count)]
        return [future.result() for future in concurrent.futures.as_completed(futures)]


def ids_from_results(results: list[dict[str, Any]], field: str) -> list[int]:
    ids: list[int] = []
    for result in results:
        body = result.get("body")
        if isinstance(body, dict) and isinstance(body.get("data"), dict):
            value = body["data"].get(field)
            if isinstance(value, int):
                ids.append(value)
    return sorted(set(ids))


def sql_scalar(query: str) -> str:
    return subprocess.check_output(
        ["docker", "exec", DB_CONTAINER, "psql", "-U", DB_USER, "-d", DB_NAME, "-tAc", query],
        text=True,
    ).strip()


def sql_json(query: str) -> Any:
    raw = sql_scalar(query)
    if not raw:
        return None
    return json.loads(raw)


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def summarize_http(results: list[dict[str, Any]]) -> dict[str, Any]:
    status_counts: dict[str, int] = {}
    request_ids: list[str] = []
    trace_ids: list[str] = []
    error_samples: list[Any] = []
    durations = sorted(result.get("durationMs", 0) for result in results)
    for result in results:
        status_counts[str(result.get("status"))] = status_counts.get(str(result.get("status")), 0) + 1
        headers = result.get("headers") or {}
        if headers.get("X-Request-Id"):
            request_ids.append(headers["X-Request-Id"])
        if headers.get("X-Trace-Id"):
            trace_ids.append(headers["X-Trace-Id"])
        if result.get("status", 0) >= 400 or result.get("status") == 0:
            error_samples.append({
                "status": result.get("status"),
                "body": result.get("body"),
                "headers": headers,
                "error": result.get("error"),
            })
    return {
        "statusCounts": status_counts,
        "durationMs": {
            "min": durations[0] if durations else None,
            "max": durations[-1] if durations else None,
        },
        "requestIdSamples": request_ids[:5],
        "traceIdSamples": trace_ids[:5],
        "errorSamples": error_samples[:5],
    }


def has_unexpected_5xx(results: list[dict[str, Any]]) -> bool:
    return any(
        isinstance(result.get("status"), int)
        and result["status"] >= 500
        and result["status"] != 503
        for result in results
    )


def scenario_duplicate_client_ride(
        evidence: dict[str, Any],
        user: User,
        evidence_key: str = "duplicateClientRideId",
) -> int | None:
    client_ride_id = f"conc-ride-{uuid4().hex}"
    payload = route_payload(client_ride_id)

    def post(index: int) -> dict[str, Any]:
        return request(
            "POST",
            "/api/v1/ride-records",
            payload,
            token=user.access_token,
            request_id=f"conc-ride-save-{index}-{client_ride_id}",
        )

    results = run_parallel("ride-save", REQUEST_COUNT, post)
    ride_ids = ids_from_results(results, "rideRecordId")
    db = sql_json(f"""
        select json_build_object(
            'rideRecordCount', count(*),
            'rideRecordIds', coalesce(json_agg(id order by id), '[]'::json),
            'pointCount', coalesce(sum(point_count), 0),
            'jobCount', coalesce(sum(job_count), 0)
        )
        from (
            select r.id,
                   (select count(*) from ride_record_points p where p.ride_record_id = r.id) as point_count,
                   (select count(*) from ride_finalization_jobs j where j.ride_record_id = r.id) as job_count
            from ride_records r
            where r.owner_user_id = {user.user_id}
              and r.client_ride_id = {sql_literal(client_ride_id)}
        ) s;
    """)
    ok = len(ride_ids) <= 1 and db["rideRecordCount"] == 1 and db["jobCount"] == 1 and not has_unexpected_5xx(results)
    evidence[evidence_key] = {
        "ok": ok,
        "clientRideId": client_ride_id,
        "http": summarize_http(results),
        "distinctRideRecordIdsFromResponses": ride_ids,
        "db": db,
        "expected": "HTTP 5xx 없이 같은 owner/clientRideId는 DB ride_records 1건, finalization job 1건만 남아야 함",
    }
    return ride_ids[0] if ride_ids else None


def poll_ready(ride_record_id: int, user: User, evidence_key: str, evidence: dict[str, Any]) -> bool:
    attempts: list[dict[str, Any]] = []
    deadline = time.time() + 60
    while time.time() < deadline:
        result = request(
            "GET",
            f"/api/v1/ride-records/{ride_record_id}",
            token=user.access_token,
            request_id=f"conc-poll-{ride_record_id}",
        )
        data = api_data(result) if result.get("status") == 200 else {}
        attempts.append({
            "status": result.get("status"),
            "data": {
                "finalizationStatus": data.get("status"),
                "rawPointCount": data.get("rawPointCount"),
                "processedPointCount": data.get("processedPointCount"),
                "finalizationAttempts": data.get("finalizationAttempts"),
                "linkedCourseId": data.get("linkedCourseId"),
            },
            "headers": result.get("headers"),
        })
        if data.get("status") == "READY":
            evidence[evidence_key] = {"ready": True, "attempts": attempts}
            return True
        if data.get("status") == "FAILED":
            evidence[evidence_key] = {"ready": False, "attempts": attempts}
            return False
        time.sleep(1)
    evidence[evidence_key] = {"ready": False, "attempts": attempts}
    return False


def scenario_duplicate_regenerate(evidence: dict[str, Any], user: User, ride_record_id: int) -> None:
    if not poll_ready(ride_record_id, user, "duplicateRegenerateInitialReady", evidence):
        evidence["duplicateRegenerate"] = {"ok": False, "skipped": "ride record did not become READY"}
        return

    def post(index: int) -> dict[str, Any]:
        return request(
            "POST",
            f"/api/v1/ride-records/{ride_record_id}/regenerate",
            {},
            token=user.access_token,
            request_id=f"conc-regenerate-{index}-{ride_record_id}",
        )

    results = run_parallel("regenerate", REQUEST_COUNT, post)
    db = sql_json(f"""
        select json_build_object(
            'jobCount', count(*),
            'statuses', coalesce(json_agg(status order by id), '[]'::json),
            'attemptCounts', coalesce(json_agg(attempt_count order by id), '[]'::json)
        )
        from ride_finalization_jobs
        where ride_record_id = {ride_record_id};
    """)
    ok = db["jobCount"] == 1 and not has_unexpected_5xx(results)
    evidence["duplicateRegenerate"] = {
        "ok": ok,
        "rideRecordId": ride_record_id,
        "http": summarize_http(results),
        "db": db,
        "expected": "동시 regenerate 후에도 ride_finalization_jobs는 rideRecord당 1건이어야 함",
    }


def scenario_duplicate_course_create(evidence: dict[str, Any], user: User, ride_record_id: int) -> int | None:
    if not poll_ready(ride_record_id, user, "duplicateCourseCreateInitialReady", evidence):
        evidence["duplicateCourseCreate"] = {"ok": False, "skipped": "ride record did not become READY"}
        return None

    def post(index: int) -> dict[str, Any]:
        return request(
            "POST",
            "/api/v1/courses",
            {
                "sourceRideRecordId": ride_record_id,
                "name": f"Concurrency course {ride_record_id}",
                "description": "동시성 검증용 코스",
                "visibility": "PUBLIC",
            },
            token=user.access_token,
            request_id=f"conc-course-create-{index}-{ride_record_id}",
        )

    results = run_parallel("course-create", REQUEST_COUNT, post)
    course_ids = ids_from_results(results, "courseId")
    db = sql_json(f"""
        select json_build_object(
            'courseCount', count(*),
            'courseIds', coalesce(json_agg(id order by id), '[]'::json),
            'routePointCount', coalesce(sum(point_count), 0)
        )
        from (
            select c.id,
                   (select count(*) from course_route_points p where p.course_id = c.id) as point_count
            from courses c
            where c.owner_user_id = {user.user_id}
              and c.source_ride_record_id = {ride_record_id}
        ) s;
    """)
    ok = len(course_ids) <= 1 and db["courseCount"] == 1 and not has_unexpected_5xx(results)
    evidence["duplicateCourseCreate"] = {
        "ok": ok,
        "rideRecordId": ride_record_id,
        "http": summarize_http(results),
        "distinctCourseIdsFromResponses": course_ids,
        "db": db,
        "expected": "같은 sourceRideRecordId는 코스 1건만 생성되고 나머지는 계약된 4xx로 끝나야 함",
    }
    return course_ids[0] if course_ids else None


def create_ready_course(evidence: dict[str, Any], user: User) -> int:
    ride_result = request(
        "POST",
        "/api/v1/ride-records",
        route_payload(f"conc-party-source-{uuid4().hex}"),
        token=user.access_token,
        request_id="conc-party-source-ride",
    )
    require_status(ride_result, 200, "party source ride create")
    ride_record_id = api_data(ride_result)["rideRecordId"]
    if not poll_ready(ride_record_id, user, "partySourceRideReady", evidence):
        raise SmokeFailure("party source ride did not become READY")
    course_result = request(
        "POST",
        "/api/v1/courses",
        {
            "sourceRideRecordId": ride_record_id,
            "name": f"Concurrency party course {ride_record_id}",
            "description": "파티 동시성 검증용 코스",
            "visibility": "PUBLIC",
        },
        token=user.access_token,
        request_id="conc-party-source-course",
    )
    require_status(course_result, 200, "party source course create")
    return api_data(course_result)["courseId"]


def scenario_party_join_leave(evidence: dict[str, Any], host: User) -> int | None:
    course_id = create_ready_course(evidence, host)
    member = register_user("member")
    party_result = request(
        "POST",
        "/api/v1/parties",
        {
            "courseId": course_id,
            "title": "Concurrency party",
            "scheduledStartAt": later_iso(3600),
            "capacity": 2,
        },
        token=host.access_token,
        request_id="conc-party-create",
    )
    require_status(party_result, 200, "party create")
    party_id = api_data(party_result)["id"]

    def join(index: int) -> dict[str, Any]:
        return request(
            "POST",
            f"/api/v1/parties/{party_id}/join",
            {},
            token=member.access_token,
            request_id=f"conc-party-join-{index}-{party_id}",
        )

    join_results = run_parallel("party-join", REQUEST_COUNT, join)
    joined_db = sql_json(f"""
        select json_build_object(
            'memberRows', count(*),
            'joinedRows', count(*) filter (where status = 'JOINED'),
            'statuses', coalesce(json_agg(status order by id), '[]'::json)
        )
        from ride_party_members
        where party_id = {party_id};
    """)

    def leave(index: int) -> dict[str, Any]:
        return request(
            "POST",
            f"/api/v1/parties/{party_id}/leave",
            {},
            token=member.access_token,
            request_id=f"conc-party-leave-{index}-{party_id}",
        )

    leave_results = run_parallel("party-leave", REQUEST_COUNT, leave)
    left_db = sql_json(f"""
        select json_build_object(
            'memberRows', count(*),
            'joinedRows', count(*) filter (where status = 'JOINED'),
            'statuses', coalesce(json_agg(status order by id), '[]'::json)
        )
        from ride_party_members
        where party_id = {party_id};
    """)
    ok = (
        joined_db["memberRows"] == 2
        and joined_db["joinedRows"] == 2
        and left_db["memberRows"] == 2
        and left_db["joinedRows"] == 1
        and not has_unexpected_5xx(join_results)
        and not has_unexpected_5xx(leave_results)
    )
    evidence["partyJoinLeave"] = {
        "ok": ok,
        "partyId": party_id,
        "courseId": course_id,
        "joinHttp": summarize_http(join_results),
        "leaveHttp": summarize_http(leave_results),
        "dbAfterJoin": joined_db,
        "dbAfterLeave": left_db,
        "expected": "같은 member 동시 join/leave 후 중복 member row가 없어야 하고 host 1명만 JOINED로 남아야 함",
    }
    return party_id


def websocket_handshake(base_url: str, party_id: int, token: str) -> dict[str, Any]:
    parsed = urllib.parse.urlparse(base_url)
    secure = parsed.scheme == "https"
    host = parsed.hostname or "127.0.0.1"
    port = parsed.port or (443 if secure else 80)
    key = base64.b64encode(os.urandom(16)).decode()
    path = f"/ws/v1/parties/{party_id}/locations"
    request_text = (
        f"GET {path} HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        f"Authorization: Bearer {token}\r\n"
        "\r\n"
    )
    started = time.perf_counter()
    raw_response = b""
    frame = b""
    try:
        sock: socket.socket = socket.create_connection((host, port), timeout=5)
        if secure:
            sock = ssl.create_default_context().wrap_socket(sock, server_hostname=host)
        with sock:
            sock.settimeout(5)
            sock.sendall(request_text.encode())
            while b"\r\n\r\n" not in raw_response:
                chunk = sock.recv(4096)
                if not chunk:
                    break
                raw_response += chunk
            try:
                frame = sock.recv(256)
            except socket.timeout:
                frame = b""
    except OSError as error:
        return {
            "status": 0,
            "durationMs": round((time.perf_counter() - started) * 1000, 2),
            "error": str(error),
        }
    head = raw_response.split(b"\r\n\r\n", 1)[0].decode(errors="replace")
    status = 0
    if head.startswith("HTTP/"):
        parts = head.split(" ", 2)
        if len(parts) > 1 and parts[1].isdigit():
            status = int(parts[1])
    opcode = frame[0] & 0x0F if frame else None
    return {
        "status": status,
        "durationMs": round((time.perf_counter() - started) * 1000, 2),
        "responseHead": head.splitlines()[:8],
        "firstFrameOpcode": opcode,
        "firstFrameHex": frame.hex()[:80],
    }


def scenario_socket_token_reuse(evidence: dict[str, Any], user: User, party_id: int) -> None:
    token_result = request(
        "POST",
        f"/api/v1/parties/{party_id}/socket-token",
        {},
        token=user.access_token,
        request_id=f"conc-party-socket-token-{party_id}",
    )
    require_status(token_result, 200, "party socket token issue")
    socket_token = api_data(token_result).get("socketToken")
    if not isinstance(socket_token, str):
        raise SmokeFailure("socket token issue 응답에 token이 없습니다.")
    first = websocket_handshake(BASE_URL, party_id, socket_token)
    second = websocket_handshake(BASE_URL, party_id, socket_token)
    ok = (
        first["status"] == 101
        and first.get("firstFrameOpcode") == 1
        and (
            second["status"] in {401, 403}
            or (second["status"] == 101 and second.get("firstFrameOpcode") == 8)
        )
    )
    evidence["socketTokenReuse"] = {
        "ok": ok,
        "partyId": party_id,
        "firstHandshake": first,
        "secondHandshake": second,
        "expected": "첫 연결만 connected frame을 받고, 재사용 연결은 handshake 거부 또는 policy close frame으로 종료되어야 함",
    }


def scenario_guard(evidence: dict[str, Any], key: str, fn: Any) -> Any:
    try:
        return fn()
    except Exception as exception:
        evidence[key] = {
            "ok": False,
            "error": exception.__class__.__name__,
            "message": str(exception),
        }
        return None


def build_summary(evidence: dict[str, Any]) -> dict[str, Any]:
    scenario_keys = [
        "duplicateClientRideId",
        "duplicateRegenerate",
        "duplicateCourseCreate",
        "partyJoinLeave",
        "socketTokenReuse",
    ]
    return {
        "ok": all(bool(evidence.get(key, {}).get("ok")) for key in scenario_keys),
        "scenarios": {
            key: bool(evidence.get(key, {}).get("ok"))
            for key in scenario_keys
        },
        "evidencePath": str(OUTPUT_PATH),
    }


def main() -> int:
    global OUTPUT_PATH

    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default=str(OUTPUT_PATH))
    args = parser.parse_args()

    OUTPUT_PATH = Path(args.output)

    evidence: dict[str, Any] = {
        "startedAt": now_iso(),
        "baseUrl": BASE_URL,
        "dbContainer": DB_CONTAINER,
        "requestCount": REQUEST_COUNT,
    }
    health = request("GET", "/health", request_id="conc-health")
    require_status(health, 200, "health")
    evidence["health"] = {
        "status": health["status"],
        "headers": health.get("headers"),
    }

    owner = register_user("owner")
    ride_id = scenario_guard(evidence, "duplicateClientRideId", lambda: scenario_duplicate_client_ride(evidence, owner))
    if isinstance(ride_id, int):
        scenario_guard(evidence, "duplicateRegenerate", lambda: scenario_duplicate_regenerate(evidence, owner, ride_id))

    course_owner = register_user("course-owner")
    course_ride_id = scenario_guard(
        evidence,
        "duplicateCourseCreateSetup",
        lambda: scenario_duplicate_client_ride(evidence, course_owner, "duplicateCourseCreateRideSave"),
    )
    if isinstance(course_ride_id, int):
        scenario_guard(evidence, "duplicateCourseCreate", lambda: scenario_duplicate_course_create(evidence, course_owner, course_ride_id))

    host = register_user("host")
    party_id = scenario_guard(evidence, "partyJoinLeave", lambda: scenario_party_join_leave(evidence, host))
    if isinstance(party_id, int):
        scenario_guard(evidence, "socketTokenReuse", lambda: scenario_socket_token_reuse(evidence, host, party_id))

    evidence["finishedAt"] = now_iso()
    evidence["summary"] = build_summary(evidence)
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(json.dumps(evidence, ensure_ascii=False, indent=2))
    print(json.dumps(evidence["summary"], ensure_ascii=False, indent=2))
    return 0 if evidence["summary"]["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Final route QA smoke harness for local backend evidence.

This script intentionally uses only the Python standard library so it can run
from a clean backend checkout. It records HTTP/DB observables, but never writes
access or refresh tokens to the evidence file.
"""

from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from uuid import uuid4


SPEED_KMH = 25.0
SPEED_MPS = SPEED_KMH * 1000.0 / 3600.0


@dataclass(frozen=True)
class RoutePoint:
    name: str
    lat: float
    lon: float


@dataclass(frozen=True)
class HttpResult:
    method: str
    path: str
    status: int
    body: dict[str, Any] | list[Any] | str | None


ROUTE_CHECKPOINTS = [
    RoutePoint("서울대입구역", 37.481247, 126.952739),
    RoutePoint("안양천합수부", 37.548300, 126.885500),
    RoutePoint("탄천합수부", 37.527900, 127.066600),
    RoutePoint("건대입구역", 37.540372, 127.069276),
]

WORKSPACE_ROOT = Path(__file__).resolve().parents[4]
DEFAULT_EVIDENCE = WORKSPACE_ROOT / ".omo/ulw-loop/evidence/G001-C001-seoul-snu-anyangcheon-tancheon-konkuk-25kmh-http-db.txt"


class SmokeFailure(Exception):
    pass


class SmokeClient:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.access_token: str | None = None

    def request(
            self,
            method: str,
            path: str,
            payload: dict[str, Any] | None = None,
            authenticated: bool = False,
    ) -> HttpResult:
        headers = {"Content-Type": "application/json"}
        if authenticated:
            if not self.access_token:
                raise SmokeFailure("authenticated request attempted before login")
            headers["Authorization"] = f"Bearer {self.access_token}"

        data = None
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")

        request = urllib.request.Request(
            f"{self.base_url}{path}",
            data=data,
            headers=headers,
            method=method,
        )

        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                body_text = response.read().decode("utf-8")
                return HttpResult(method, path, response.status, parse_json(body_text))
        except urllib.error.HTTPError as error:
            body_text = error.read().decode("utf-8", errors="replace")
            return HttpResult(method, path, error.code, parse_json(body_text))
        except urllib.error.URLError as error:
            raise SmokeFailure(f"HTTP connection failed for {method} {path}: {error}") from error


def parse_json(value: str) -> dict[str, Any] | list[Any] | str | None:
    if not value:
        return None
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError:
        return value
    return parsed


def api_data(result: HttpResult) -> dict[str, Any]:
    if not isinstance(result.body, dict):
        raise SmokeFailure(f"{result.method} {result.path} returned non-object body")
    data = result.body.get("data")
    if not isinstance(data, dict):
        raise SmokeFailure(f"{result.method} {result.path} returned missing data object")
    return data


def require_status(result: HttpResult, expected: int = 200) -> None:
    if result.status != expected:
        raise SmokeFailure(f"{result.method} {result.path} returned HTTP {result.status}, expected {expected}")


def haversine_m(a: RoutePoint, b: RoutePoint) -> float:
    radius_m = 6371000.0
    lat1 = math.radians(a.lat)
    lat2 = math.radians(b.lat)
    delta_lat = math.radians(b.lat - a.lat)
    delta_lon = math.radians(b.lon - a.lon)
    h = math.sin(delta_lat / 2.0) ** 2
    h += math.cos(lat1) * math.cos(lat2) * math.sin(delta_lon / 2.0) ** 2
    return 2.0 * radius_m * math.asin(math.sqrt(h))


def build_route_payload(started_at: datetime) -> tuple[list[dict[str, Any]], int, int]:
    route_points: list[dict[str, Any]] = []
    elapsed_sec = 0.0
    total_distance_m = 0.0

    for index, checkpoint in enumerate(ROUTE_CHECKPOINTS, start=1):
        if index > 1:
            previous = ROUTE_CHECKPOINTS[index - 2]
            distance = haversine_m(previous, checkpoint)
            total_distance_m += distance
            elapsed_sec += distance / SPEED_MPS

        captured_at = started_at + timedelta(seconds=round(elapsed_sec))
        route_points.append({
            "pointOrder": index,
            "latitude": round(checkpoint.lat, 7),
            "longitude": round(checkpoint.lon, 7),
            "capturedAt": iso(captured_at),
            "accuracyM": 5,
            "speedMps": round(SPEED_MPS, 2),
        })

    return route_points, round(total_distance_m), max(1, round(elapsed_sec))


def iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def register_smoke_user(client: SmokeClient, evidence: dict[str, Any]) -> tuple[str, int]:
    email = f"ulw-route-smoke-{int(time.time())}-{uuid4().hex[:8]}@example.com"
    payload = {
        "email": email,
        "password": "Password123!",
        "displayName": "ULWRouteSmoke",
    }
    result = client.request("POST", "/api/v1/auth/register", payload)
    evidence["register"] = summarize(result, include_data_keys=["userId", "displayName", "accessExpiresInSec"])
    require_status(result)
    data = api_data(result)
    access_token = data.get("accessToken")
    user_id = data.get("userId")
    if not isinstance(access_token, str) or not access_token:
        raise SmokeFailure("register response did not include accessToken")
    if not isinstance(user_id, int):
        raise SmokeFailure("register response did not include numeric userId")
    client.access_token = access_token
    return email, user_id


def create_ride_record(client: SmokeClient, evidence: dict[str, Any]) -> int:
    started_at = datetime.now(timezone.utc).replace(microsecond=0)
    route_points, distance_m, duration_sec = build_route_payload(started_at)
    ended_at = started_at + timedelta(seconds=duration_sec)
    payload = {
        "clientRideId": f"ulw-route-{uuid4().hex}",
        "startedAt": iso(started_at),
        "endedAt": iso(ended_at),
        "summary": {
            "distanceM": distance_m,
            "durationSec": duration_sec,
        },
        "routePoints": route_points,
    }
    result = client.request("POST", "/api/v1/ride-records", payload, authenticated=True)
    evidence["routeSimulation"] = {
        "speedKmh": SPEED_KMH,
        "checkpoints": [point.name for point in ROUTE_CHECKPOINTS],
        "distanceM": distance_m,
        "durationSec": duration_sec,
        "routePointCount": len(route_points),
        "startedAt": payload["startedAt"],
        "endedAt": payload["endedAt"],
    }
    evidence["rideRecordCreate"] = summarize(result, include_data_keys=["rideRecordId", "routePointCount", "finalizationStatus"])
    require_status(result)
    data = api_data(result)
    ride_record_id = data.get("rideRecordId")
    if not isinstance(ride_record_id, int):
        raise SmokeFailure("ride record create response did not include rideRecordId")
    return ride_record_id


def poll_ready(client: SmokeClient, ride_record_id: int, evidence: dict[str, Any]) -> dict[str, Any]:
    attempts: list[dict[str, Any]] = []
    deadline = time.time() + 60.0
    last_data: dict[str, Any] | None = None

    while time.time() < deadline:
        result = client.request("GET", f"/api/v1/ride-records/{ride_record_id}", authenticated=True)
        require_status(result)
        data = api_data(result)
        last_data = data
        attempts.append(pick(data, ["status", "rawPointCount", "processedPointCount", "finalizationAttempts", "errorMessage"]))
        status = data.get("status")
        if status == "READY":
            evidence["rideRecordPolling"] = {"attempts": attempts}
            return data
        if status == "FAILED":
            evidence["rideRecordPolling"] = {"attempts": attempts}
            raise SmokeFailure(f"ride record finalization failed: {data.get('errorMessage')}")
        time.sleep(1.0)

    evidence["rideRecordPolling"] = {"attempts": attempts}
    raise SmokeFailure(f"ride record did not become READY before timeout; last={last_data}")


def create_course(client: SmokeClient, ride_record_id: int, evidence: dict[str, Any]) -> int:
    payload = {
        "sourceRideRecordId": ride_record_id,
        "name": "ULW 서울대입구-건대입구 25kmh smoke",
        "description": "ULW final route QA smoke course",
        "visibility": "PUBLIC",
    }
    result = client.request("POST", "/api/v1/courses", payload, authenticated=True)
    evidence["courseCreate"] = summarize(result, include_data_keys=["courseId", "visibility", "title", "sourceRideRecordId"])
    require_status(result)
    data = api_data(result)
    course_id = data.get("courseId")
    if not isinstance(course_id, int):
        raise SmokeFailure("course create response did not include courseId")
    return course_id


def fetch_route_points(client: SmokeClient, course_id: int, evidence: dict[str, Any]) -> list[dict[str, Any]]:
    result = client.request("GET", f"/api/v1/courses/{course_id}/route-points", authenticated=True)
    evidence["courseRoutePoints"] = summarize(result, include_data_keys=["courseId", "points"])
    require_status(result)
    data = api_data(result)
    points = data.get("points")
    if not isinstance(points, list) or len(points) < 2:
        raise SmokeFailure("course route-points response did not include at least two points")
    point_orders = [point.get("pointOrder") for point in points if isinstance(point, dict)]
    if point_orders != sorted(point_orders):
        raise SmokeFailure(f"course route-points order is not ascending: {point_orders}")
    evidence["courseRoutePoints"]["data"]["pointCount"] = len(points)
    evidence["courseRoutePoints"]["data"]["pointOrders"] = point_orders
    return points


def run_c002_rehearsal(client: SmokeClient, evidence: dict[str, Any]) -> None:
    result = client.request("GET", "/api/v1/courses?limit=10")
    require_status(result)
    data = api_data(result)
    items = data.get("items")
    if not isinstance(items, list):
        raise SmokeFailure("courses list response did not include items")

    checked_courses: list[dict[str, Any]] = []
    skipped_courses: list[dict[str, Any]] = []
    for item in items:
        if len(checked_courses) >= 2:
            break
        if not isinstance(item, dict) or not isinstance(item.get("id"), int):
            continue
        course_id = item["id"]
        detail_result = client.request("GET", f"/api/v1/courses/{course_id}")
        if detail_result.status != 200:
            skipped_courses.append({"courseId": course_id, "reason": "detail_not_readable", "status": detail_result.status})
            continue
        detail_data = api_data(detail_result)
        if detail_data.get("sourceRideRecordId") is not None:
            skipped_courses.append({"courseId": course_id, "reason": "generated_from_smoke_or_ride_record"})
            continue
        route_result = client.request("GET", f"/api/v1/courses/{course_id}/route-points")
        if route_result.status != 200:
            skipped_courses.append({"courseId": course_id, "reason": "route_points_not_readable", "status": route_result.status})
            continue
        route_data = api_data(route_result)
        points = route_data.get("points")
        if not isinstance(points, list) or len(points) < 2:
            skipped_courses.append({"courseId": course_id, "reason": "route_points_less_than_two"})
            continue
        probes = evaluate_course_follow(client, course_id, points)
        monotonic = is_progress_monotonic(probes)
        checked_courses.append({
            "courseId": course_id,
            "title": item.get("title"),
            "pointCount": len(points),
            "progressMonotonic": monotonic,
            "probes": probes,
        })

    malformed = client.request(
        "POST",
        "/api/v1/courses/1/ride-policy/evaluate",
        {
            "phase": "ACTIVE",
            "location": {
                "lat": 91,
                "lon": 127,
                "accuracyM": 5,
                "capturedAt": iso(datetime.now(timezone.utc)),
            },
            "trace": [],
        },
    )

    limitation = c002_limitation(checked_courses)
    c002_status = "PASS" if limitation is None and malformed.status == 400 else "PENDING"

    evidence["existingCourseFollowRehearsal"] = {
        "status": c002_status,
        "availableCourseCount": len(items),
        "checkedCourseCount": len(checked_courses),
        "checkedCourses": checked_courses,
        "skippedCourses": skipped_courses,
        "limitation": limitation,
        "malformedCoordinate": summarize(malformed),
    }


def evaluate_course_follow(client: SmokeClient, course_id: int, points: list[dict[str, Any]]) -> list[dict[str, Any]]:
    selected = [
        ("start", points[0]),
        ("mid", points[len(points) // 2]),
        ("end", points[-1]),
    ]
    trace: list[dict[str, Any]] = []
    probes: list[dict[str, Any]] = []
    for label, point in selected:
        location = {
            "lat": point["latitude"],
            "lon": point["longitude"],
            "accuracyM": 5,
            "capturedAt": iso(datetime.now(timezone.utc)),
        }
        trace.append(location)
        result = client.request(
            "POST",
            f"/api/v1/courses/{course_id}/ride-policy/evaluate",
            {"phase": "ACTIVE", "location": location, "trace": trace},
        )
        require_status(result)
        data = api_data(result)
        progress = data.get("progress") if isinstance(data.get("progress"), dict) else {}
        probes.append({
            "label": label,
            "overallState": data.get("overallState"),
            "offRoute": data.get("offRoute", {}).get("status") if isinstance(data.get("offRoute"), dict) else None,
            "completion": data.get("completion", {}).get("status") if isinstance(data.get("completion"), dict) else None,
            "progressPercent": progress.get("progressPercent"),
            "nearestSegmentIndex": progress.get("nearestSegmentIndex"),
        })
    return probes


def is_progress_monotonic(probes: list[dict[str, Any]]) -> bool:
    progress_values = [
        probe.get("progressPercent")
        for probe in probes
        if isinstance(probe.get("progressPercent"), int)
    ]
    segment_values = [
        probe.get("nearestSegmentIndex")
        for probe in probes
        if isinstance(probe.get("nearestSegmentIndex"), int)
    ]
    return progress_values == sorted(progress_values) and segment_values == sorted(segment_values)


def c002_limitation(checked_courses: list[dict[str, Any]]) -> str | None:
    if len(checked_courses) < 2:
        return "sourceRideRecordId가 없는 기존 실제 코스 중 route-points를 가진 코스가 2개 미만이라 C002 PASS 증거로는 부족합니다."
    if any(not course.get("progressMonotonic") for course in checked_courses):
        return "기존 코스 follow probe 중 progressPercent 또는 nearestSegmentIndex가 역행해 C002 PASS 증거로는 부족합니다."
    return None


def query_postgis(args: argparse.Namespace, course_id: int, ride_record_id: int, evidence: dict[str, Any]) -> None:
    sql = (
        "select c.id, c.source_ride_record_id, count(crp.id), "
        "c.route_line_geom is not null, coalesce(ST_NPoints(c.route_line_geom), 0) "
        "from courses c "
        "left join course_route_points crp on crp.course_id = c.id "
        f"where c.id = {course_id} and c.source_ride_record_id = {ride_record_id} "
        "group by c.id, c.source_ride_record_id, c.route_line_geom;"
    )
    output = run_psql(args, sql)
    columns = output.split("|") if output else []
    evidence["postgis"] = {
        "raw": output,
        "courseId": columns[0] if len(columns) > 0 else None,
        "sourceRideRecordId": columns[1] if len(columns) > 1 else None,
        "routePointCount": columns[2] if len(columns) > 2 else None,
        "routeLineGeomNotNull": columns[3] if len(columns) > 3 else None,
        "stNPoints": columns[4] if len(columns) > 4 else None,
    }
    if len(columns) < 5 or columns[3] != "t" or int(columns[4]) < 2:
        raise SmokeFailure(f"PostGIS route_line_geom verification failed: {output}")


def run_psql(args: argparse.Namespace, sql: str) -> str:
    command = [
        "docker",
        "exec",
        args.db_container,
        "psql",
        "-U",
        args.db_user,
        "-d",
        args.db_name,
        "-tAc",
        sql,
    ]
    completed = subprocess.run(command, check=False, text=True, capture_output=True)
    if completed.returncode != 0:
        raise SmokeFailure(f"psql failed: {completed.stderr.strip()}")
    return completed.stdout.strip()


def cleanup_smoke_user(args: argparse.Namespace, email: str, evidence: dict[str, Any]) -> None:
    if args.keep_data:
        evidence["cleanup"] = {"status": "skipped", "reason": "--keep-data"}
        return

    sql = f"""
do $$
declare
    smoke_user_id bigint;
begin
    select id into smoke_user_id from users where email = '{email.replace("'", "''")}';
    if smoke_user_id is not null then
        delete from client_events where user_id = smoke_user_id or ride_record_id in (
            select id from ride_records where owner_user_id = smoke_user_id
        );
        delete from course_route_points where course_id in (
            select id from courses where owner_user_id = smoke_user_id
        );
        delete from courses where owner_user_id = smoke_user_id;
        delete from ride_record_processed_points where ride_record_id in (
            select id from ride_records where owner_user_id = smoke_user_id
        );
        delete from ride_record_points where ride_record_id in (
            select id from ride_records where owner_user_id = smoke_user_id
        );
        delete from ride_records where owner_user_id = smoke_user_id;
        delete from user_consents where user_id = smoke_user_id;
        delete from kakao_account_links where user_id = smoke_user_id;
        delete from users where id = smoke_user_id;
    end if;
end $$;
select count(*) from users where email = '{email.replace("'", "''")}';
"""
    remaining = run_psql(args, sql).splitlines()[-1].strip()
    evidence["cleanup"] = {
        "status": "completed",
        "email": email,
        "remainingUserRows": remaining,
    }
    if remaining != "0":
        raise SmokeFailure(f"cleanup did not remove smoke user; remaining={remaining}")


def summarize(result: HttpResult, include_data_keys: list[str] | None = None) -> dict[str, Any]:
    summary: dict[str, Any] = {
        "method": result.method,
        "path": result.path,
        "status": result.status,
    }
    if isinstance(result.body, dict):
        summary["code"] = result.body.get("code")
        summary["message"] = result.body.get("message")
        if include_data_keys:
            data = result.body.get("data")
            if isinstance(data, dict):
                summary["data"] = pick(data, include_data_keys)
    else:
        summary["body"] = result.body
    return summary


def pick(data: dict[str, Any], keys: list[str]) -> dict[str, Any]:
    return {key: data.get(key) for key in keys if key in data}


def write_evidence(path: Path, evidence: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def run(args: argparse.Namespace) -> int:
    evidence_path = Path(args.evidence)
    evidence: dict[str, Any] = {
        "status": "RUNNING",
        "startedAt": iso(datetime.now(timezone.utc)),
        "baseUrl": args.base_url,
        "notes": [
            "토큰은 evidence에 기록하지 않는다.",
            "C001은 최종 QA PASS에 필요한 HTTP+DB 증거를 생성한다.",
            "C002는 기존 코스가 충분할 때만 PASS 후보 증거가 된다.",
        ],
    }
    email: str | None = None
    exit_code = 0

    try:
        client = SmokeClient(args.base_url)
        email, user_id = register_smoke_user(client, evidence)
        evidence["smokeUser"] = {"email": email, "userId": user_id}
        ride_record_id = create_ride_record(client, evidence)
        ready = poll_ready(client, ride_record_id, evidence)
        course_id = create_course(client, ride_record_id, evidence)
        points = fetch_route_points(client, course_id, evidence)
        query_postgis(args, course_id, ride_record_id, evidence)
        run_c002_rehearsal(client, evidence)
        c002 = evidence.get("existingCourseFollowRehearsal")
        evidence["result"] = {
            "rideRecordId": ride_record_id,
            "courseId": course_id,
            "finalizationStatus": ready.get("status"),
            "processedPointCount": ready.get("processedPointCount"),
            "courseRoutePointCount": len(points),
            "c001Status": "PASS",
            "c002Status": c002.get("status") if isinstance(c002, dict) else "PENDING",
        }
        evidence["status"] = "PASS"
    except Exception as exception:  # noqa: BLE001 - smoke evidence should capture any failure.
        exit_code = 1
        evidence["status"] = "FAIL"
        evidence["failure"] = str(exception)
    finally:
        if email is not None:
            try:
                cleanup_smoke_user(args, email, evidence)
            except Exception as cleanup_error:  # noqa: BLE001
                exit_code = 1
                evidence["cleanup"] = {
                    "status": "failed",
                    "error": str(cleanup_error),
                }
                evidence["status"] = "FAIL"
        evidence["finishedAt"] = iso(datetime.now(timezone.utc))
        write_evidence(evidence_path, evidence)

    print(f"evidence={evidence_path}")
    print(f"status={evidence['status']}")
    return exit_code


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run final route QA HTTP/DB smoke against a live backend.")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080", help="Live backend base URL.")
    parser.add_argument(
        "--evidence",
        default=str(DEFAULT_EVIDENCE),
        help="Evidence JSON file path. Tokens are never written.",
    )
    parser.add_argument("--db-container", default="bike-back-postgres", help="Docker Postgres container name.")
    parser.add_argument("--db-user", default="bike", help="Postgres user for docker exec psql.")
    parser.add_argument("--db-name", default="bike", help="Postgres database for docker exec psql.")
    parser.add_argument("--keep-data", action="store_true", help="Do not delete smoke user data after the run.")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

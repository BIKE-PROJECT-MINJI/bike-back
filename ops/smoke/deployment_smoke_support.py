#!/usr/bin/env python3
"""공통 smoke HTTP 호출, 응답 계약 검증, evidence 정리 헬퍼."""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from typing import Any


class SmokeFailure(Exception):
    pass


def request(method: str, url: str, payload: dict[str, Any] | None = None, headers: dict[str, str] | None = None) -> dict[str, Any]:
    request_headers = dict(headers or {})
    data = None
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode()
        request_headers.setdefault("Content-Type", "application/json")
    http_request = urllib.request.Request(url, data=data, headers=request_headers, method=method)
    try:
        with urllib.request.urlopen(http_request, timeout=25) as response:
            raw = response.read()
            return {
                "method": method,
                "url": url,
                "status": response.status,
                "bodyBytes": len(raw),
                "headers": dict(response.headers),
                "body": parse_json(raw.decode(errors="replace")),
            }
    except urllib.error.HTTPError as error:
        raw = error.read()
        return {
            "method": method,
            "url": url,
            "status": error.code,
            "bodyBytes": len(raw),
            "headers": dict(error.headers),
            "body": parse_json(raw.decode(errors="replace")),
        }


def parse_json(value: str) -> Any:
    if not value:
        return None
    try:
        return json.loads(value)
    except json.JSONDecodeError:
        return value


def api_data(result: dict[str, Any]) -> dict[str, Any]:
    body = result.get("body")
    if not isinstance(body, dict) or not isinstance(body.get("data"), dict):
        raise SmokeFailure(f"{result['method']} {result['url']} 응답에 data object가 없습니다.")
    return body["data"]


def verify_api_response(
        result: dict[str, Any],
        expected: int,
        label: str,
        *,
        data_object_required: bool = True,
        trace_headers_required: bool = True,
) -> dict[str, Any]:
    require_status(result, expected, label)
    body = require_api_contract(result, expected, label, data_object_required=data_object_required)
    if trace_headers_required:
        require_trace_headers(result, label)
    return body


def require_status(result: dict[str, Any], expected: int, label: str) -> None:
    if result["status"] != expected:
        raise SmokeFailure(f"{label} status={result['status']}, expected={expected}")


def require_api_contract(
        result: dict[str, Any],
        expected_code: int,
        label: str,
        *,
        data_object_required: bool,
) -> dict[str, Any]:
    body = result.get("body")
    if not isinstance(body, dict):
        raise SmokeFailure(f"{label} 응답 본문이 JSON object가 아닙니다.")
    if body.get("code") != expected_code:
        raise SmokeFailure(f"{label} code={body.get('code')}, expected={expected_code}")
    if not isinstance(body.get("message"), str) or not body.get("message"):
        raise SmokeFailure(f"{label} message가 비어 있거나 문자열이 아닙니다.")
    if "data" not in body:
        raise SmokeFailure(f"{label} 응답에 data 필드가 없습니다.")
    if data_object_required and not isinstance(body.get("data"), dict):
        raise SmokeFailure(f"{label} data가 object가 아닙니다.")
    return body


def require_trace_headers(result: dict[str, Any], label: str) -> None:
    headers = result.get("headers")
    if not isinstance(headers, dict):
        raise SmokeFailure(f"{label} 응답 header를 읽을 수 없습니다.")
    for header_name in ("X-Request-Id", "X-Trace-Id"):
        if not isinstance(headers.get(header_name), str) or not headers[header_name]:
            raise SmokeFailure(f"{label} 응답에 {header_name} header가 없습니다.")


def sanitize_headers(headers: dict[str, str]) -> dict[str, str]:
    allowed = {
        "Access-Control-Allow-Origin",
        "Access-Control-Allow-Methods",
        "Access-Control-Allow-Headers",
        "Access-Control-Allow-Credentials",
        "Content-Type",
        "X-Request-Id",
        "X-Trace-Id",
    }
    return {key: value for key, value in headers.items() if key in allowed}


def slim(result: dict[str, Any]) -> dict[str, Any]:
    return {
        "status": result["status"],
        "bodyBytes": result["bodyBytes"],
        "headers": sanitize_headers(result.get("headers", {})),
        "contract": contract_summary(result),
        "body": result.get("body"),
    }


def contract_summary(result: dict[str, Any]) -> dict[str, Any] | None:
    body = result.get("body")
    if not isinstance(body, dict):
        return None
    return {
        "hasCode": isinstance(body.get("code"), int),
        "hasMessage": isinstance(body.get("message"), str),
        "hasData": "data" in body,
        "dataType": type(body.get("data")).__name__,
    }

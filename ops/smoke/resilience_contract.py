#!/usr/bin/env python3
"""Gate B fail-closed, print-only resilience drill contract."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
from pathlib import Path
from typing import Mapping, Sequence
from urllib.parse import urlsplit

REQUIRED_ENV = (
    "TEST_ID", "TARGET_ENV", "SOURCE_COMMIT_SHA", "ARTIFACT_SHA256", "BUDGET",
    "TTL_SECONDS", "CLEANUP_OWNER", "CREDENTIAL_OWNER", "SLO", "BIKE_BASE_URL",
    "READINESS_URL",
)
SHA256_RE = re.compile(r"[0-9a-f]{64}")
COMMIT_RE = re.compile(r"[0-9a-f]{40}")
SAFE_ALIAS_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
BUDGET_RE = re.compile(r"requests=([1-9][0-9]{0,5}),cost_usd=(0|[1-9][0-9]{0,2})\.[0-9]{2}")
SLO_RE = re.compile(r"health_ms=([1-9][0-9]{0,5}),ready_ms=([1-9][0-9]{0,5})")
SECRET_RE = re.compile(
    r"(?i)(AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|sk-[A-Za-z0-9_-]{16,}|"
    r"Bearer\s+[A-Za-z0-9._~+/-]+=*|eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}|"
    r"(?:ghp|github_pat|xox[baprs]|session)_[A-Za-z0-9_-]{16,}|"
    r"-----BEGIN [A-Z ]*PRIVATE KEY-----|(?:set-cookie|cookie)\s*[:=]|"
    r"(?:password|secret|token|api[_-]?key)\s*[:=]\s*\S+|"
    r"(?:jdbc:postgresql|redis|https?)://[^\s/@:]+:[^\s/@]+@|"
    r"[\"']?(?:latitude|longitude|lat|lon)[\"']?\s*[:=]\s*-?[0-9]+(?:\.[0-9]+)?)"
)
STOP_KEYS = (
    "raw500", "duplicateJobs", "orphanJobs", "dataLoss", "rollbackIncompatible",
    "albUnhealthy", "residualResourceUnknown",
)
EVIDENCE_FIELDS = {
    "schemaVersion", "healthStatus", "readyStatus", "dbJobAssertions",
    "cleanupReceipt", "stopFlags", "locationTraceIncluded",
}
DB_JOB_FIELDS = {
    "duplicateJobs", "orphanJobs", "runningLeaseUnknown", "dataLoss", "rollbackCompatible",
}
READ_ONLY_PREFIXES = (("aws", "elbv2", "describe-target-health"),)
MUTATING_WORDS = {
    "deregister-targets", "register-targets", "stop", "start", "restart", "migrate",
    "repair", "apply", "destroy", "delete", "reboot", "terminate-instances",
}


class ContractError(ValueError):
    pass


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def assert_secret_safe(value: str, label: str = "value") -> None:
    if SECRET_RE.search(value):
        raise ContractError(f"{label} contains secret, credential URL, token, cookie, private key, or location trace")


def validate_public_alias_url(value: str, label: str) -> None:
    parsed = urlsplit(value)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
        raise ContractError(f"{label} must be a credential-free HTTP(S) URL")
    host = parsed.hostname.lower()
    if host.startswith("prod") or ".prod" in host or "production" in host:
        raise ContractError(f"{label} must not identify production")
    if not (host.endswith(".invalid") or host.endswith(".test")):
        raise ContractError(f"{label} must use a reserved .invalid or .test Gate B alias")


def preflight(env: Mapping[str, str]) -> dict[str, str]:
    missing = [name for name in REQUIRED_ENV if not env.get(name, "").strip()]
    if missing:
        raise ContractError("missing required non-secret environment names: " + ", ".join(missing))
    for key in ("TEST_ID", "TARGET_ENV", "CLEANUP_OWNER", "CREDENTIAL_OWNER"):
        if not SAFE_ALIAS_RE.fullmatch(env[key]):
            raise ContractError(f"{key} must be a bounded non-secret alias")
    target = env["TARGET_ENV"].lower()
    if target.startswith("prod") or "production" in target:
        raise ContractError("TARGET_ENV must be a non-production alias")
    if not COMMIT_RE.fullmatch(env["SOURCE_COMMIT_SHA"]):
        raise ContractError("SOURCE_COMMIT_SHA must be an immutable lowercase 40-hex commit")
    if not SHA256_RE.fullmatch(env["ARTIFACT_SHA256"]):
        raise ContractError("ARTIFACT_SHA256 must be an immutable lowercase SHA-256")
    budget_match = BUDGET_RE.fullmatch(env["BUDGET"])
    if not budget_match or int(budget_match.group(1)) > 100000 or float(budget_match.group(2)) > 100.0:
        raise ContractError("BUDGET must be bounded requests=N,cost_usd=N.NN")
    slo_match = SLO_RE.fullmatch(env["SLO"])
    if not slo_match or max(map(int, slo_match.groups())) > 300000:
        raise ContractError("SLO must be bounded health_ms=N,ready_ms=N")
    try:
        ttl = int(env["TTL_SECONDS"])
    except ValueError as exc:
        raise ContractError("TTL_SECONDS must be an integer") from exc
    if ttl < 60 or ttl > 86400:
        raise ContractError("TTL_SECONDS must be between 60 and 86400")
    validate_public_alias_url(env["BIKE_BASE_URL"], "BIKE_BASE_URL")
    validate_public_alias_url(env["READINESS_URL"], "READINESS_URL")
    for key in REQUIRED_ENV:
        assert_secret_safe(env[key], key)
    return {key: env[key] for key in REQUIRED_ENV}


def validate_matrix(matrix: Mapping[str, object]) -> None:
    required = {"id", "precondition", "injection", "expected", "forbidden", "stop", "evidence", "restore", "cleanup"}
    scenarios = list(matrix.get("failure", [])) + list(matrix.get("recoveryDeployment", []))  # type: ignore[arg-type]
    if len(scenarios) != 10:
        raise ContractError("matrix must have exactly ten required scenarios")
    for scenario in scenarios:
        if not isinstance(scenario, dict) or required - set(scenario):
            raise ContractError(f"matrix scenario missing fields: {scenario}")
        for key, value in scenario.items():
            if not isinstance(value, (str, int)):
                raise ContractError(f"matrix field {key} must be scalar")
            assert_secret_safe(str(value), f"matrix.{scenario.get('id')}.{key}")


def validate_evidence_bytes(raw: bytes) -> dict[str, object]:
    if len(raw) > 1_000_000:
        raise ContractError("evidence exceeds 1 MB")
    try:
        text = raw.decode("utf-8")
        evidence = json.loads(text)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ContractError("evidence must be UTF-8 JSON") from exc
    assert_secret_safe(text, "evidence")
    if not isinstance(evidence, dict) or set(evidence) != EVIDENCE_FIELDS:
        raise ContractError("evidence fields do not match typed allowlist schema")
    if evidence["schemaVersion"] != 1 or evidence["locationTraceIncluded"] is not False:
        raise ContractError("evidence schema/location contract failed")
    if type(evidence["healthStatus"]) is not int or type(evidence["readyStatus"]) is not int:
        raise ContractError("health/readiness must be integer status values")
    db_jobs = evidence["dbJobAssertions"]
    if not isinstance(db_jobs, dict) or set(db_jobs) != DB_JOB_FIELDS:
        raise ContractError("DB/job assertions do not match typed schema")
    if any(type(db_jobs[key]) is not expected for key, expected in {
        "duplicateJobs": int, "orphanJobs": int, "runningLeaseUnknown": bool,
        "dataLoss": bool, "rollbackCompatible": bool,
    }.items()):
        raise ContractError("DB/job assertion types are invalid")
    if db_jobs["duplicateJobs"] < 0 or db_jobs["orphanJobs"] < 0:
        raise ContractError("DB/job counts must be non-negative")
    flags = evidence["stopFlags"]
    if not isinstance(flags, dict) or set(flags) != set(STOP_KEYS) or any(type(value) is not bool for value in flags.values()):
        raise ContractError("stop flags do not match typed schema")
    if evidence["cleanupReceipt"] not in {"VERIFIED", "PENDING", "UNKNOWN"}:
        raise ContractError("cleanup receipt must be VERIFIED, PENDING, or UNKNOWN")
    return evidence


def stop_reasons(exit_code: int, evidence: Mapping[str, object]) -> list[str]:
    reasons: list[str] = []
    if exit_code != 0:
        reasons.append("nonzeroExit")
    if evidence.get("healthStatus") != 200:
        reasons.append("healthLivenessFailed")
    if evidence.get("readyStatus") != 200:
        reasons.append("readinessFailed")
    if evidence.get("cleanupReceipt") != "VERIFIED":
        reasons.append("cleanupIncomplete")
    flags = evidence.get("stopFlags")
    if not isinstance(flags, Mapping):
        reasons.append("stopFlagsUnknown")
    else:
        reasons.extend(key for key in STOP_KEYS if flags.get(key) is not False)
    db_jobs = evidence.get("dbJobAssertions")
    if not isinstance(db_jobs, Mapping):
        reasons.append("dbJobAssertionsUnknown")
    else:
        if db_jobs.get("duplicateJobs") != 0:
            reasons.append("duplicateJobsAssertionFailed")
        if db_jobs.get("orphanJobs") != 0:
            reasons.append("orphanJobsAssertionFailed")
        if db_jobs.get("runningLeaseUnknown") is not False:
            reasons.append("runningLeaseUnknown")
        if db_jobs.get("dataLoss") is not False:
            reasons.append("dataLossAssertionFailed")
        if db_jobs.get("rollbackCompatible") is not True:
            reasons.append("rollbackCompatibilityUnknown")
    return reasons


def build_manifest(command_name: str, exit_code: int, env: Mapping[str, str], artifact: Path) -> dict[str, object]:
    safe = preflight(env)
    if not SAFE_ALIAS_RE.fullmatch(command_name):
        raise ContractError("commandName must contain a name only, never arguments")
    raw = artifact.read_bytes()
    evidence = validate_evidence_bytes(raw)
    reasons = stop_reasons(exit_code, evidence)
    manifest = {
        "schemaVersion": 1,
        "commandName": command_name,
        "exitCode": exit_code,
        "timestampUtc": utc_now(),
        "testId": safe["TEST_ID"],
        "environmentAlias": safe["TARGET_ENV"],
        "sourceCommit": safe["SOURCE_COMMIT_SHA"],
        "redactionState": "VERIFIED",
        "artifactChecksum": safe["ARTIFACT_SHA256"],
        "evidenceFileChecksum": hashlib.sha256(raw).hexdigest(),
        "dbJobAssertions": evidence["dbJobAssertions"],
        "health": evidence["healthStatus"],
        "readiness": evidence["readyStatus"],
        "cleanupReceipt": evidence["cleanupReceipt"],
        "stopReasons": reasons,
        "result": "PASS" if not reasons else "STOP",
    }
    assert_secret_safe(json.dumps(manifest, sort_keys=True), "manifest")
    return manifest


def print_only_plan(command: Sequence[str], execute: bool, env: Mapping[str, str]) -> dict[str, object]:
    safe = preflight(env)
    if execute:
        raise ContractError("Gate B disables all external command execution; Gate C lifecycle approval is required")
    if not command:
        return {"mode": "print-only", "commandName": "none", "testId": safe["TEST_ID"]}
    if any(word in MUTATING_WORDS for word in command):
        raise ContractError("mutating command is disabled in Gate B")
    if not any(tuple(command[:len(prefix)]) == prefix for prefix in READ_ONLY_PREFIXES):
        raise ContractError("only read-only describe plans are allowlisted")
    if len(command) != 3:
        raise ContractError("Gate B describe plan accepts no target, profile, endpoint, or resource arguments")
    for part in command:
        assert_secret_safe(part, "command plan")
        if any(character in part for character in ("\n", "\r", "\x00")):
            raise ContractError("command plan contains a control character")
    return {"mode": "print-only", "commandName": Path(command[0]).name, "testId": safe["TEST_ID"]}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix", default=str(Path(__file__).with_name("resilience-matrix.json")))
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    matrix = json.loads(Path(args.matrix).read_text(encoding="utf-8"))
    validate_matrix(matrix)
    command = args.command[1:] if args.command and args.command[0] == "--" else args.command
    result = print_only_plan(command, args.execute, os.environ)
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

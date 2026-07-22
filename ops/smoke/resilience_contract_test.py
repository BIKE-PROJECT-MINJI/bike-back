import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import resilience_contract as contract


def valid_env():
    return {
        "TEST_ID": "resilience-01",
        "TARGET_ENV": "staging-a",
        "SOURCE_COMMIT_SHA": "b" * 40,
        "ARTIFACT_SHA256": "a" * 64,
        "BUDGET": "requests=100,cost_usd=0.00",
        "TTL_SECONDS": "600",
        "CLEANUP_OWNER": "backend-ops",
        "CREDENTIAL_OWNER": "platform-ops",
        "SLO": "health_ms=1000,ready_ms=60000",
        "BIKE_BASE_URL": "https://service.invalid",
        "READINESS_URL": "https://service.invalid/health/monitor",
    }


def valid_evidence():
    return {
        "schemaVersion": 1,
        "healthStatus": 200,
        "readyStatus": 200,
        "dbJobAssertions": {
            "duplicateJobs": 0,
            "orphanJobs": 0,
            "runningLeaseUnknown": False,
            "dataLoss": False,
            "rollbackCompatible": True,
        },
        "cleanupReceipt": "VERIFIED",
        "stopFlags": {key: False for key in contract.STOP_KEYS},
        "locationTraceIncluded": False,
    }


class ResilienceContractTest(unittest.TestCase):
    def test_matrix_has_complete_contract(self):
        matrix = json.loads(Path(contract.__file__).with_name("resilience-matrix.json").read_text())
        contract.validate_matrix(matrix)
        self.assertEqual(6, len(matrix["failure"]))
        self.assertEqual(4, len(matrix["recoveryDeployment"]))

    def test_preflight_fails_closed_for_every_required_name(self):
        for name in contract.REQUIRED_ENV:
            env = valid_env()
            env.pop(name)
            with self.subTest(name=name), self.assertRaises(contract.ContractError):
                contract.preflight(env)

    def test_preflight_rejects_prod_alias_host_unbounded_values_and_secret_url(self):
        cases = [
            {"TARGET_ENV": "prod-blue"},
            {"BIKE_BASE_URL": "https://production.example.invalid"},
            {"BIKE_BASE_URL": "https://api.real-service.com"},
            {"BIKE_BASE_URL": "https://10.0.0.1"},
            {"BIKE_BASE_URL": "https://service.amazonaws.com"},
            {"ARTIFACT_SHA256": "main"},
            {"SOURCE_COMMIT_SHA": "head"},
            {"TTL_SECONDS": "0"},
            {"BUDGET": "requests=999999,cost_usd=999.00"},
            {"SLO": "health_ms=999999,ready_ms=999999"},
            {"BIKE_BASE_URL": "https://user:password@example.invalid"},
        ]
        for update in cases:
            with self.subTest(update=update), self.assertRaises(contract.ContractError):
                contract.preflight({**valid_env(), **update})

    def test_gate_b_never_executes_and_rejects_all_mutations(self):
        mutations = [
            ["systemctl", "restart", "bike-worker"],
            ["systemctl", "restart", "extra-unit"],
            ["aws", "elbv2", "deregister-targets", "--target-group-arn", "anything"],
            ["aws", "elbv2", "register-targets", "--profile", "prod"],
        ]
        for command in mutations:
            with self.subTest(command=command), self.assertRaises(contract.ContractError):
                contract.print_only_plan(command, False, valid_env())
        with self.assertRaises(contract.ContractError):
            contract.print_only_plan(["aws", "elbv2", "describe-target-health"], True, valid_env())

    def test_read_only_describe_is_print_only_and_rejects_endpoint_profile_target_args(self):
        result = contract.print_only_plan(["aws", "elbv2", "describe-target-health"], False, valid_env())
        self.assertEqual("print-only", result["mode"])
        for option in ("--endpoint-url", "--profile", "--target-group-arn"):
            with self.subTest(option=option), self.assertRaises(contract.ContractError):
                contract.print_only_plan(
                    ["aws", "elbv2", "describe-target-health", option, "TARGET_ALIAS"],
                    False,
                    valid_env(),
                )

    def test_stale_or_replayed_approval_cannot_enable_execution(self):
        env = {**valid_env(), "BIKE_RESILIENCE_EXECUTION_APPROVAL": "APPROVE:stale:replayed"}
        with self.assertRaises(contract.ContractError):
            contract.print_only_plan(["aws", "elbv2", "describe-target-health"], True, env)

    def test_final_pass_requires_every_explicit_normal_assertion(self):
        evidence = valid_evidence()
        self.assertEqual([], contract.stop_reasons(0, evidence))
        cases = [
            (1, evidence),
            (0, {**evidence, "readyStatus": 503}),
            (0, {**evidence, "cleanupReceipt": "PENDING"}),
            (0, {key: value for key, value in evidence.items() if key != "dbJobAssertions"}),
        ]
        for exit_code, item in cases:
            with self.subTest(item=item):
                self.assertTrue(contract.stop_reasons(exit_code, item))

    def test_typed_evidence_and_manifest_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory, "evidence.json")
            artifact.write_text(json.dumps(valid_evidence()), encoding="utf-8")
            manifest = contract.build_manifest("worker-recovery", 0, valid_env(), artifact)
            self.assertEqual(hashlib.sha256(artifact.read_bytes()).hexdigest(), manifest["evidenceFileChecksum"])
            self.assertEqual("VERIFIED", manifest["redactionState"])
            self.assertEqual("PASS", manifest["result"])

    def test_evidence_rejects_missing_unknown_wrong_type_and_secret_bytes(self):
        samples = []
        missing = valid_evidence()
        missing.pop("cleanupReceipt")
        samples.append(missing)
        unknown = {**valid_evidence(), "unknown": "field"}
        samples.append(unknown)
        wrong_type = valid_evidence()
        wrong_type["dbJobAssertions"]["duplicateJobs"] = False
        samples.append(wrong_type)
        bool_status = valid_evidence()
        bool_status["healthStatus"] = True
        samples.append(bool_status)
        negative_count = valid_evidence()
        negative_count["dbJobAssertions"]["orphanJobs"] = -1
        samples.append(negative_count)
        arbitrary_cleanup = valid_evidence()
        arbitrary_cleanup["cleanupReceipt"] = "MAYBE"
        samples.append(arbitrary_cleanup)
        for item in samples:
            with self.subTest(item=item), self.assertRaises(contract.ContractError):
                contract.validate_evidence_bytes(json.dumps(item).encode())
        secrets = [
            "Bearer abc.def.ghi", "eyJabcdefgh.abcdefgh.abcdefgh", "-----BEGIN PRIVATE KEY-----",
            "Cookie: session=value", "latitude=37.1234", "ghp_abcdefghijklmnopqrstuvwxyz1234567890",
            "xoxb_abcdefghijklmnopqrstuvwxyz1234567890", "session_abcdefghijklmnopqrstuvwxyz1234567890",
        ]
        for secret in secrets:
            item = valid_evidence()
            item["cleanupReceipt"] = secret
            raw = json.dumps(item).encode()
            with self.subTest(secret=secret), self.assertRaises(contract.ContractError):
                contract.validate_evidence_bytes(raw)


if __name__ == "__main__":
    unittest.main()

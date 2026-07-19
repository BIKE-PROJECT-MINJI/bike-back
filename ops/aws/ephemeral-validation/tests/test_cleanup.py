from __future__ import annotations

import os
import sys
import unittest
from pathlib import Path
from typing import cast
from unittest.mock import patch

from botocore.exceptions import ClientError

LAMBDA_DIR = Path(__file__).resolve().parents[1] / "lambda"
sys.path.insert(0, str(LAMBDA_DIR))

import cleanup  # noqa: E402


def client_error(code: str, operation: str) -> ClientError:
    return ClientError(
        error_response={"Error": {"Code": code, "Message": code}},
        operation_name=operation,
    )


class EmptyEc2:
    def describe_instances(self, **_: object) -> dict[str, list[object]]:
        return {"Reservations": []}

    def describe_volumes(self, **_: object) -> dict[str, list[object]]:
        return {"Volumes": []}

    def describe_addresses(self, **_: object) -> dict[str, list[object]]:
        return {"Addresses": []}

    def describe_nat_gateways(self, **_: object) -> dict[str, list[object]]:
        return {"NatGateways": []}

    def describe_vpc_endpoints(self, **_: object) -> dict[str, list[object]]:
        return {"VpcEndpoints": []}


class EmptyElbv2:
    def describe_load_balancers(self, **_: object) -> dict[str, list[object]]:
        return {"LoadBalancers": []}


class EmptySsm:
    def get_parameters_by_path(self, **_: object) -> dict[str, list[object]]:
        return {"Parameters": []}


class PersistentS3:
    def list_objects_v2(self, **_: object) -> dict[str, object]:
        return {
            "Contents": [{"Key": "runs/test/artifact.tar.gz"}],
            "KeyCount": 1,
            "IsTruncated": False,
        }

    def delete_objects(self, **_: object) -> dict[str, object]:
        return {}

    def delete_bucket(self, **_: object) -> None:
        raise client_error("AccessDenied", "DeleteBucket")

    def head_bucket(self, **_: object) -> dict[str, object]:
        return {}


class FakeSession:
    def __init__(self, s3: PersistentS3) -> None:
        self._clients: dict[str, object] = {
            "ec2": EmptyEc2(),
            "elbv2": EmptyElbv2(),
            "s3": s3,
            "ssm": EmptySsm(),
        }

    def client(self, service_name: str) -> object:
        return self._clients[service_name]


class Context:
    aws_request_id = "test-request"


class CleanupTest(unittest.TestCase):
    def test_persistent_s3_bucket_never_returns_verified_cleanup(self) -> None:
        session = FakeSession(PersistentS3())
        environment = {
            "RUN_ID": "test-run",
            "ARTIFACT_BUCKET": "test-bucket",
            "ARTIFACT_PREFIX": "runs/test-run",
            "SECRET_PREFIX": "/gaja/ephemeral/test-run/",
        }

        with (
            patch.dict(os.environ, environment, clear=False),
            patch.object(cleanup, "Session", return_value=session),
            patch.object(cleanup.time, "sleep", return_value=None),
        ):
            with self.assertRaises(cleanup.CleanupIncompleteError) as raised:
                _ = cleanup.handler(
                    cast(cleanup.CleanupEvent, {"run_id": "test-run"}),
                    Context(),
                )

        self.assertEqual(raised.exception.residuals["s3_bucket"], 1)
        self.assertEqual(raised.exception.residuals["s3_objects"], 1)


if __name__ == "__main__":
    unittest.main()

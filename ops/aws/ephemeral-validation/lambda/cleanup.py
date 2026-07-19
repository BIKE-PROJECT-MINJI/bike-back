# /// script
# requires-python = ">=3.12"
# dependencies = ["boto3>=1.40,<2", "boto3-stubs[ec2,elbv2,s3,ssm]>=1.40,<2"]
# ///
# --- How to run ---
# uv run cleanup.py is not a standalone command; invoke handler with a Lambda event.

from __future__ import annotations

import os
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import TYPE_CHECKING, Final, Protocol, TypedDict, override

from boto3.session import Session
from botocore.exceptions import ClientError

NOT_FOUND_CODES: Final[frozenset[str]] = frozenset(
    {
        "AccessPointNotFound",
        "404",
        "LoadBalancerNotFound",
        "NotFound",
        "NoSuchBucket",
        "ParameterNotFound",
    }
)

if TYPE_CHECKING:
    from mypy_boto3_ec2 import EC2Client
    from mypy_boto3_elbv2 import ElasticLoadBalancingv2Client
    from mypy_boto3_s3 import S3Client
    from mypy_boto3_s3.type_defs import ObjectIdentifierTypeDef
    from mypy_boto3_ssm import SSMClient


class LambdaContext(Protocol):
    aws_request_id: str


class CleanupEvent(TypedDict):
    run_id: str


class CleanupResult(TypedDict):
    run_id: str
    status: str
    residual_total: int
    warning_count: int


@dataclass(frozen=True, slots=True)
class CleanupConfig:
    run_id: str
    artifact_bucket: str
    artifact_prefix: str
    secret_prefix: str

    @classmethod
    def from_environment(cls) -> CleanupConfig:
        return cls(
            run_id=os.environ["RUN_ID"],
            artifact_bucket=os.environ["ARTIFACT_BUCKET"],
            artifact_prefix=os.environ["ARTIFACT_PREFIX"],
            secret_prefix=os.environ["SECRET_PREFIX"],
        )


def handler(event: CleanupEvent, _context: LambdaContext) -> CleanupResult:
    config = CleanupConfig.from_environment()
    _assert_run_id(event=event, config=config)

    session = Session()
    ec2: EC2Client = session.client("ec2")  # pyright: ignore[reportUnknownMemberType]
    elbv2: ElasticLoadBalancingv2Client = session.client(  # pyright: ignore[reportUnknownMemberType]
        "elbv2"
    )
    s3: S3Client = session.client("s3")  # pyright: ignore[reportUnknownMemberType]
    ssm: SSMClient = session.client("ssm")  # pyright: ignore[reportUnknownMemberType]

    warnings: list[str] = []
    residuals: dict[str, int] = {}
    for attempt in range(50):
        _request_paid_resource_cleanup(
            ec2=ec2,
            elbv2=elbv2,
            run_id=config.run_id,
            warnings=warnings,
        )
        residuals = _count_paid_residuals(
            ec2=ec2,
            elbv2=elbv2,
            run_id=config.run_id,
        )
        if sum(residuals.values()) == 0:
            break
        if attempt < 49:
            time.sleep(10)

    if sum(residuals.values()) != 0:
        raise CleanupIncompleteError(residuals=residuals)

    _run_best_effort(
        warnings=warnings,
        resource_type="ssm_parameters",
        operation=lambda: _delete_parameters(ssm=ssm, prefix=config.secret_prefix),
    )
    s3_residuals: dict[str, int] = {}
    for attempt in range(6):
        _run_best_effort(
            warnings=warnings,
            resource_type="s3_artifacts",
            operation=lambda: _delete_run_artifacts(
                s3=s3,
                bucket=config.artifact_bucket,
                prefix=config.artifact_prefix,
            ),
        )
        _run_best_effort(
            warnings=warnings,
            resource_type="s3_bucket",
            operation=lambda: _delete_artifact_bucket(
                s3=s3,
                bucket=config.artifact_bucket,
            ),
        )
        s3_residuals = _count_s3_residuals(s3=s3, bucket=config.artifact_bucket)
        if sum(s3_residuals.values()) == 0:
            break
        if attempt < 5:
            time.sleep(5)

    residuals.update(s3_residuals)
    residual_total = sum(residuals.values())
    if residual_total != 0:
        raise CleanupIncompleteError(residuals=residuals)
    return {
        "run_id": config.run_id,
        "status": "chargeable_resource_cleanup_verified",
        "residual_total": residual_total,
        "warning_count": len(warnings),
    }


def _request_paid_resource_cleanup(
    ec2: EC2Client,
    elbv2: ElasticLoadBalancingv2Client,
    run_id: str,
    warnings: list[str],
) -> None:
    for load_balancer_arn in _find_load_balancer_arns(
        elbv2=elbv2,
        run_id=run_id,
    ):
        _run_best_effort(
            warnings=warnings,
            resource_type="load_balancer",
            operation=lambda arn=load_balancer_arn: _delete_load_balancer(
                elbv2=elbv2,
                load_balancer_arn=arn,
            ),
        )

    instance_ids = _find_instance_ids(ec2=ec2, run_id=run_id)
    if instance_ids:
        _run_best_effort(
            warnings=warnings,
            resource_type="instance",
            operation=lambda: ec2.terminate_instances(InstanceIds=list(instance_ids)),
        )

    vpc_endpoint_ids = _find_vpc_endpoint_ids(ec2=ec2, run_id=run_id)
    if vpc_endpoint_ids:
        _run_best_effort(
            warnings=warnings,
            resource_type="vpc_endpoint",
            operation=lambda: _delete_vpc_endpoints(
                ec2=ec2,
                vpc_endpoint_ids=vpc_endpoint_ids,
            ),
        )

    for volume_id in _find_available_volume_ids(ec2=ec2, run_id=run_id):
        _run_best_effort(
            warnings=warnings,
            resource_type="volume",
            operation=lambda identifier=volume_id: ec2.delete_volume(
                VolumeId=identifier
            ),
        )

    for allocation_id in _find_allocation_ids(ec2=ec2, run_id=run_id):
        _run_best_effort(
            warnings=warnings,
            resource_type="elastic_ip",
            operation=lambda identifier=allocation_id: ec2.release_address(
                AllocationId=identifier
            ),
        )

    for nat_gateway_id in _find_nat_gateway_ids(ec2=ec2, run_id=run_id):
        _run_best_effort(
            warnings=warnings,
            resource_type="nat_gateway",
            operation=lambda identifier=nat_gateway_id: ec2.delete_nat_gateway(
                NatGatewayId=identifier
            ),
        )


def _run_best_effort(
    warnings: list[str],
    resource_type: str,
    operation: Callable[[], object],
) -> None:
    try:
        _ = operation()
    except ClientError as error:
        error_code = error.response.get("Error", {}).get("Code", "Unknown")
        if error_code not in NOT_FOUND_CODES:
            warnings.append(f"{resource_type}:{error_code}")
    except CleanupResourceDeletionError:
        warnings.append(f"{resource_type}:partial_delete")


def _delete_vpc_endpoints(
    ec2: EC2Client,
    vpc_endpoint_ids: tuple[str, ...],
) -> None:
    result = ec2.delete_vpc_endpoints(VpcEndpointIds=list(vpc_endpoint_ids))
    if result.get("Unsuccessful"):
        raise CleanupResourceDeletionError(resource_type="vpc_endpoint")


def _count_paid_residuals(
    ec2: EC2Client,
    elbv2: ElasticLoadBalancingv2Client,
    run_id: str,
) -> dict[str, int]:
    return {
        "instances": len(_find_instance_ids(ec2=ec2, run_id=run_id)),
        "volumes": len(_find_volume_ids(ec2=ec2, run_id=run_id)),
        "elastic_ips": len(_find_allocation_ids(ec2=ec2, run_id=run_id)),
        "nat_gateways": len(_find_nat_gateway_ids(ec2=ec2, run_id=run_id)),
        "vpc_endpoints": len(_find_vpc_endpoint_ids(ec2=ec2, run_id=run_id)),
        "load_balancers": len(_find_load_balancer_arns(elbv2=elbv2, run_id=run_id)),
    }


def _assert_run_id(event: CleanupEvent, config: CleanupConfig) -> None:
    event_run_id = event.get("run_id")
    if event_run_id != config.run_id:
        raise CleanupRunIdMismatchError(expected=config.run_id, actual=event_run_id)


def _find_instance_ids(ec2: EC2Client, run_id: str) -> tuple[str, ...]:
    response = ec2.describe_instances(
        Filters=[
            {"Name": "tag:RunId", "Values": [run_id]},
            {
                "Name": "instance-state-name",
                "Values": ["pending", "running", "stopping", "stopped"],
            },
        ]
    )
    return tuple(
        instance["InstanceId"]
        for reservation in response.get("Reservations", [])
        for instance in reservation.get("Instances", [])
        if "InstanceId" in instance
    )


def _find_vpc_endpoint_ids(ec2: EC2Client, run_id: str) -> tuple[str, ...]:
    response = ec2.describe_vpc_endpoints(
        Filters=[{"Name": "tag:RunId", "Values": [run_id]}]
    )
    return tuple(
        endpoint_id
        for endpoint in response.get("VpcEndpoints", [])
        if (endpoint_id := endpoint.get("VpcEndpointId")) is not None
    )


def _find_volume_ids(ec2: EC2Client, run_id: str) -> tuple[str, ...]:
    response = ec2.describe_volumes(Filters=[{"Name": "tag:RunId", "Values": [run_id]}])
    return tuple(
        volume_id
        for volume in response.get("Volumes", [])
        if (volume_id := volume.get("VolumeId")) is not None
    )


def _find_available_volume_ids(ec2: EC2Client, run_id: str) -> tuple[str, ...]:
    response = ec2.describe_volumes(
        Filters=[
            {"Name": "tag:RunId", "Values": [run_id]},
            {"Name": "status", "Values": ["available"]},
        ]
    )
    return tuple(
        volume_id
        for volume in response.get("Volumes", [])
        if (volume_id := volume.get("VolumeId")) is not None
    )


def _find_allocation_ids(ec2: EC2Client, run_id: str) -> tuple[str, ...]:
    response = ec2.describe_addresses(
        Filters=[{"Name": "tag:RunId", "Values": [run_id]}]
    )
    return tuple(
        allocation_id
        for address in response.get("Addresses", [])
        if (allocation_id := address.get("AllocationId")) is not None
    )


def _find_nat_gateway_ids(ec2: EC2Client, run_id: str) -> tuple[str, ...]:
    response = ec2.describe_nat_gateways(MaxResults=1000)
    return tuple(
        nat_gateway_id
        for gateway in response.get("NatGateways", [])
        if gateway.get("State") in {"pending", "available", "deleting"}
        if any(
            tag.get("Key") == "RunId" and tag.get("Value") == run_id
            for tag in gateway.get("Tags", [])
        )
        if (nat_gateway_id := gateway.get("NatGatewayId")) is not None
    )


def _find_load_balancer_arns(
    elbv2: ElasticLoadBalancingv2Client,
    run_id: str,
) -> tuple[str, ...]:
    response = elbv2.describe_load_balancers(PageSize=400)
    arns = [
        load_balancer["LoadBalancerArn"]
        for load_balancer in response.get("LoadBalancers", [])
        if "LoadBalancerArn" in load_balancer
    ]
    matching: list[str] = []
    for start in range(0, len(arns), 20):
        batch = arns[start : start + 20]
        if not batch:
            continue
        tags_response = elbv2.describe_tags(ResourceArns=batch)
        for description in tags_response.get("TagDescriptions", []):
            tags = {
                tag["Key"]: tag["Value"]
                for tag in description.get("Tags", [])
                if "Key" in tag and "Value" in tag
            }
            if tags.get("RunId") == run_id and "ResourceArn" in description:
                matching.append(description["ResourceArn"])
    return tuple(matching)


def _delete_parameters(ssm: SSMClient, prefix: str) -> None:
    next_token: str | None = None
    while True:
        if next_token is None:
            response = ssm.get_parameters_by_path(
                Path=prefix,
                Recursive=True,
                WithDecryption=False,
            )
        else:
            response = ssm.get_parameters_by_path(
                Path=prefix,
                Recursive=True,
                WithDecryption=False,
                NextToken=next_token,
            )
        names = [
            name
            for parameter in response.get("Parameters", [])
            if (name := parameter.get("Name")) is not None
        ]
        for start in range(0, len(names), 10):
            _ = ssm.delete_parameters(Names=names[start : start + 10])
        next_token = response.get("NextToken")
        if next_token is None:
            return


def _delete_run_artifacts(s3: S3Client, bucket: str, prefix: str) -> None:
    continuation_token: str | None = None
    while True:
        if continuation_token is None:
            response = s3.list_objects_v2(Bucket=bucket, Prefix=f"{prefix}/")
        else:
            response = s3.list_objects_v2(
                Bucket=bucket,
                Prefix=f"{prefix}/",
                ContinuationToken=continuation_token,
            )
        objects: list[ObjectIdentifierTypeDef] = [
            {"Key": key}
            for item in response.get("Contents", [])
            if (key := item.get("Key")) is not None
        ]
        if objects:
            _ = s3.delete_objects(
                Bucket=bucket,
                Delete={"Objects": objects, "Quiet": True},
            )
        if not response.get("IsTruncated", False):
            return
        continuation_token = response["NextContinuationToken"]


def _delete_load_balancer(
    elbv2: ElasticLoadBalancingv2Client,
    load_balancer_arn: str,
) -> None:
    try:
        _ = elbv2.delete_load_balancer(LoadBalancerArn=load_balancer_arn)
    except ClientError as error:
        error_code = error.response.get("Error", {}).get("Code", "")
        if error_code not in NOT_FOUND_CODES:
            raise


def _delete_artifact_bucket(s3: S3Client, bucket: str) -> None:
    try:
        _ = s3.delete_bucket(Bucket=bucket)
    except ClientError as error:
        error_code = error.response.get("Error", {}).get("Code", "")
        if error_code not in NOT_FOUND_CODES:
            raise


def _count_s3_residuals(s3: S3Client, bucket: str) -> dict[str, int]:
    try:
        _ = s3.head_bucket(Bucket=bucket)
    except ClientError as error:
        error_code = error.response.get("Error", {}).get("Code", "")
        if error_code in NOT_FOUND_CODES:
            return {"s3_bucket": 0, "s3_objects": 0}
        raise

    object_count = 0
    continuation_token: str | None = None
    while True:
        if continuation_token is None:
            response = s3.list_objects_v2(Bucket=bucket)
        else:
            response = s3.list_objects_v2(
                Bucket=bucket,
                ContinuationToken=continuation_token,
            )
        object_count += int(response.get("KeyCount", 0))
        if not response.get("IsTruncated", False):
            break
        continuation_token = response["NextContinuationToken"]
    return {"s3_bucket": 1, "s3_objects": object_count}


@dataclass(frozen=True, slots=True)
class CleanupRunIdMismatchError(Exception):
    expected: str
    actual: str | None

    @override
    def __str__(self) -> str:
        return f"cleanup event run_id {self.actual!r} does not match {self.expected!r}"


@dataclass(frozen=True, slots=True)
class CleanupResourceDeletionError(Exception):
    resource_type: str

    @override
    def __str__(self) -> str:
        return f"one or more {self.resource_type} resources could not be deleted"


@dataclass(frozen=True, slots=True)
class CleanupIncompleteError(Exception):
    residuals: dict[str, int]

    @override
    def __str__(self) -> str:
        return f"chargeable resource cleanup incomplete: {self.residuals}"

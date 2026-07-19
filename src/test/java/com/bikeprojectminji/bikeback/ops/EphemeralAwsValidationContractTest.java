package com.bikeprojectminji.bikeback.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EphemeralAwsValidationContractTest {

    private static final Path STACK = Path.of("ops/aws/ephemeral-validation");

    @Test
    @DisplayName("임시 검증 스택은 no-NAT private node와 기존 ACM HTTPS만 허용한다")
    void stackUsesPrivateNodesAndExistingCertificate() throws Exception {
        String network = read("network.tf") + read("locals.tf");
        String compute = read("compute.tf");
        String edge = read("edge.tf");

        assertThat(network).contains("aws_vpc_endpoint", "Gateway", "ssmmessages", "private_dns_enabled = true");
        assertThat(network).doesNotContain("aws_nat_gateway");
        assertThat(compute).contains("associate_public_ip_address", "= false");
        assertThat(edge).contains(
                "var.existing_acm_certificate_arn",
                "for_each = var.attach_app_targets ? aws_instance.app : {}"
        );
        assertThat(edge).doesNotContain("resource \"aws_acm_certificate\"");
    }

    @Test
    @DisplayName("비밀은 Terraform state와 user-data를 거치지 않고 SSM SecureString 경로로만 읽는다")
    void stackKeepsSecretsOutsideTerraformState() throws Exception {
        String compute = read("compute.tf");
        String iam = read("iam.tf");
        String variables = read("variables.tf");

        assertThat(compute).contains("secret_parameter_prefix", "bootstrap_object_key");
        assertThat(iam).contains("ssm:GetParameter", "ssm:GetParametersByPath", "kms:Decrypt");
        assertThat(variables).doesNotContain("db_password", "redis_password", "api_key");
    }

    @Test
    @DisplayName("비용과 TTL gate는 20퍼센트 여유·3달러·165분 정리·180분 상한을 강제한다")
    void stackEnforcesCostAndCleanupGates() throws Exception {
        String variables = read("variables.tf");
        String cleanup = read("cleanup.tf");
        String preflight = read("scripts/preflight.sh");

        assertThat(variables).contains("cost_limit_usd", "default     = 3", "ttl_minutes", "default     = 180");
        assertThat(cleanup).contains("aws_scheduler_schedule", "cleanup_start_at");
        assertThat(preflight).contains("COST_HEADROOM=1.20", "pricing get-products", "165", "180");
    }

    @Test
    @DisplayName("모든 과금 자원은 RunId 태그와 fallback reaper 입력으로 연결된다")
    void stackTagsAndReapsPaidResources() throws Exception {
        String locals = read("locals.tf");
        String cleanup = read("cleanup.tf");
        String cleanupFunction = read("lambda/cleanup.py");

        assertThat(locals).contains("RunId", "var.run_id", "ExpiresAt");
        assertThat(cleanup).contains("RUN_ID", "aws_scheduler_schedule");
        assertThat(cleanupFunction).contains(
                "describe_instances",
                "describe_load_balancers",
                "describe_vpc_endpoints",
                "terminate_instances",
                "delete_load_balancer",
                "delete_vpc_endpoints",
                "s3_bucket",
                "_count_s3_residuals"
        );
    }

    @Test
    @DisplayName("control plane은 S3 artifact보다 cleanup scheduler를 먼저 고정한다")
    void controlPlaneProtectsArtifactsBeforeBucketCreation() throws Exception {
        String controlPlane = read("scripts/prepare-control-plane.sh");
        String bootstrapGate = read("bootstrap-gate.tf");

        assertThat(controlPlane).contains(
                "-target=aws_scheduler_schedule.cleanup",
                "-target=aws_lambda_permission.scheduler",
                "get-schedule",
                "create-bucket",
                "\"Tags\": [",
                "ssm put-parameter"
        );
        assertThat(controlPlane).doesNotContain("ssm add-tags-to-resource", "request[\"Overwrite\"]");
        assertThat(controlPlane.indexOf("-target=aws_scheduler_schedule.cleanup"))
                .isLessThan(controlPlane.indexOf("create-bucket"));
        assertThat(bootstrapGate).contains(
                "data \"external\" \"bootstrap_prerequisites\"",
                "check-bootstrap-prerequisites.py",
                "resource \"terraform_data\" \"bootstrap_prerequisites\""
        );
    }

    @Test
    @DisplayName("k6 evidence 권한과 장시간 SSM polling은 컨테이너 실행 계약에 맞는다")
    void k6UsesWritableEvidenceAndLongPolling() throws Exception {
        String runner = read("scripts/run-k6-stage.sh");

        assertThat(runner).contains(
                "install -d -m 0770 -o 12345 -g 12345",
                "get-command-invocation",
                "Pending|InProgress|Delayed",
                "Failed|Cancelled|TimedOut"
        );
        assertThat(runner).doesNotContain("aws ssm wait command-executed");
    }

    @Test
    @DisplayName("관측성 gate는 구성된 앱 수만큼 Prometheus UP target을 요구한다")
    void observabilityRequiresEveryAppTarget() throws Exception {
        String bootstrap = read("bootstrap/observability.sh");
        String gate = read("scripts/verify-bootstrap-and-attach.sh");

        assertThat(bootstrap).contains("EXPECTED_APP_TARGETS", "healthy_targets");
        assertThat(gate).contains(
                "/opt/gaja-run/verify-observability.sh",
                "EXPECTED_APP_TARGETS",
                "healthy_targets"
        );
    }

    @Test
    @DisplayName("ALB target은 SSM 의존성 gate가 모두 통과한 뒤에만 등록한다")
    void targetAttachmentRequiresRuntimeDependencyGate() throws Exception {
        String gate = read("scripts/verify-bootstrap-and-attach.sh");
        String outputs = read("outputs.tf");

        assertThat(gate).contains(
                "aws ssm send-command",
                "/opt/gaja-run/graphhopper.ready",
                "/opt/gaja-run/db.ready",
                "/opt/gaja-run/redis.ready",
                "http://127.0.0.1:8091/health",
                "http://127.0.0.1:8080/ready",
                "attach_app_targets=true",
                "aws elbv2 wait target-in-service"
        );
        assertThat(outputs).contains("output \"instance_ids\"");
    }

    @Test
    @DisplayName("종료는 Terraform destroy 뒤 RunId 잔존 자원 0건을 감사한다")
    void teardownDestroysAndAuditsAllRunResources() throws Exception {
        String teardown = read("scripts/destroy-and-audit.sh");

        assertThat(teardown).contains(
                "terraform -chdir=\"$STACK_DIR\" destroy",
                "describe-instances",
                "describe-volumes",
                "describe-addresses",
                "describe-nat-gateways",
                "describe-vpc-endpoints",
                "describe-subnets",
                "describe-route-tables",
                "describe-internet-gateways",
                "describe-security-groups",
                "describe-network-interfaces",
                "describe-${resource_kind}",
                "load-balancers",
                "target-groups",
                "get-parameters-by-path",
                "list-schedules",
                "list-functions",
                "list-instance-profiles",
                "mapfile -t sorted_resources",
                "residual_total",
                "exit 1"
        );
        assertThat(teardown).doesNotContain("} | tee \"$EVIDENCE_DIR/residual-audit.json\"");
        assertThat(teardown).contains("-var=destroy_mode=true", "artifact bucket still exists");
        assertThat(teardown.indexOf("aws s3 rm"))
                .isLessThan(teardown.indexOf("terraform -chdir=\"$STACK_DIR\" destroy"));
    }

    private static String read(String relativePath) throws Exception {
        return Files.readString(STACK.resolve(relativePath));
    }
}

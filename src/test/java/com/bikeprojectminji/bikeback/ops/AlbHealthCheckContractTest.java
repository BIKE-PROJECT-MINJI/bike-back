package com.bikeprojectminji.bikeback.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AlbHealthCheckContractTest {

    @Test
    @DisplayName("ALB health check는 dependency 장애 전파를 막기 위해 liveness 경로를 사용한다")
    void albUsesLivenessHealthCheckByDefault() throws Exception {
        String variables = Files.readString(Path.of("ops/aws/alb-acm-route53/variables.tf"));
        String example = Files.readString(Path.of("ops/aws/alb-acm-route53/terraform.tfvars.example"));
        String main = Files.readString(Path.of("ops/aws/alb-acm-route53/main.tf"));

        Pattern defaultPath = Pattern.compile("(?m)^\\s*default\\s*=\\s*\"/health\"\\s*$");
        Pattern examplePath = Pattern.compile("(?m)^health_check_path\\s*=\\s*\"/health\"\\s*$");

        assertThat(defaultPath.matcher(variables).results()).hasSize(1);
        assertThat(examplePath.matcher(example).results()).hasSize(1);
        assertThat(variables).doesNotContain("\"/ready\"");
        assertThat(example).doesNotContain("\"/ready\"");
        assertThat(main).contains("path                = var.health_check_path");
    }
}

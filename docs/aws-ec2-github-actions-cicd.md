# AWS EC2 + GitHub Actions CI/CD

## 목적

이 문서는 `bike-back`를 **저비용 AWS EC2 환경**에 배포할 때 사용하는 GitHub Actions CI/CD 기준을 정리합니다.

기본 경로는 아래와 같습니다.

1. GitHub Actions CI가 `test`, `bootJar`를 실행합니다.
2. `main` 반영 시 GitHub Actions CD가 GitHub OIDC로 AWS IAM role을 가정합니다.
3. 빌드된 jar를 private S3 bucket에 업로드합니다.
4. AWS SSM Run Command가 app EC2에서 새 jar를 내려받아 `systemd` 서비스를 재시작합니다.
5. 내부 `/health`와 외부 `HEALTHCHECK_URL`을 함께 확인합니다.

## 왜 이 방식을 쓰는가

- GitHub Secrets에 장기 AWS access key를 넣지 않기 위해서입니다.
- GitHub runner의 동적 IP 때문에 SSH 인바운드를 GitHub에 넓게 열지 않기 위해서입니다.
- ALB, ECS, CodeDeploy, ECR 없이도 저비용으로 안전한 자동 배포를 만들기 위해서입니다.

## GitHub Actions 파일

- `.github/workflows/backend-ci.yml`
- `.github/workflows/backend-cd.yml`

## GitHub 설정값

### Secret

- `AWS_DEPLOY_ROLE_ARN`

### Variable

- `APP_INSTANCE_ID`
- `DEPLOY_S3_BUCKET`
- `HEALTHCHECK_URL`
- `APP_SERVICE_NAME` (기본값: `bike-back`)
- `APP_DEPLOY_DIR` (기본값: `/opt/bike-back`)
- `APP_PORT` (기본값: `8080`)

## AWS 선행 조건

### 1. GitHub OIDC IAM role

GitHub Actions가 assume 할 수 있는 IAM role이 필요합니다.

최소 권한 기준은 아래 범위만 먼저 검토합니다.

- `s3:PutObject`
- `s3:GetObject`
- `s3:ListBucket`
- `ssm:SendCommand`
- `ssm:GetCommandInvocation`
- `ssm:ListCommandInvocations`
- `ec2:DescribeInstances`

권한 범위는 deploy bucket과 app EC2 instance로 최대한 좁힙니다.

### 2. app EC2 조건

- SSM Agent가 동작 중이어야 합니다.
- 인스턴스 프로파일에 S3 read 권한이 있어야 합니다.
- `systemd` 서비스명이 `bike-back` 또는 `APP_SERVICE_NAME`과 일치해야 합니다.
- 애플리케이션 jar 경로는 `/opt/bike-back/current.jar` 기준으로 맞추는 것을 권장합니다.

### 3. S3 bucket 조건

- private bucket이어야 합니다.
- 예시 경로: `s3://<bucket>/bike-back/releases/<git-sha>/app.jar`

## app EC2 systemd 예시

```ini
[Unit]
Description=bike-back application
After=network.target

[Service]
User=ubuntu
WorkingDirectory=/opt/bike-back
EnvironmentFile=/opt/bike-back/.env
ExecStart=/usr/bin/java -jar /opt/bike-back/current.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

`User`는 실제 서버 사용자에 맞춰 바꿉니다.

## 배포 시 동작

1. 새 jar를 S3에 업로드합니다.
2. SSM Run Command가 app EC2에서 새 jar를 `/opt/bike-back/releases/<sha>/app.jar`로 내려받습니다.
3. `/opt/bike-back/current.jar` 심볼릭 링크를 새 jar로 교체합니다.
4. `systemctl restart bike-back`을 실행합니다.
5. `http://127.0.0.1:<APP_PORT>/health`를 확인합니다.
6. 실패하면 이전 `current.jar`로 롤백하고 서비스를 다시 시작합니다.

## 롤백 방식

- 기본 롤백은 **직전 symlink target 복구 + systemd 재시작**입니다.
- 앱 외부 공개 health는 `HEALTHCHECK_URL`로 마지막에 다시 확인합니다.
- DB migration이 포함된 배포라면 jar 롤백만으로 충분한지 별도 점검해야 합니다.

## 주의사항

- 이 흐름은 app EC2 배포 자동화 기준입니다.
- db EC2 schema 변경은 Flyway migration 영향이 있으므로, 대규모 schema 변경 시에는 배포 전 백업 기준을 먼저 잠가야 합니다.
- Redis는 현재 코드와 `/health/monitor`에 연결 흔적이 있으므로, 운영에서 제거할 때는 앱 계약과 monitor 기준을 먼저 정리해야 합니다.
- Redis AOF baseline은 앱 jar CD와 별도입니다. current 기준의 concrete 설정은 `dev/bike-back/ops/redis/redis.aof.conf` (`appendonly yes`, `appendfsync everysec`) 를 따르고, self-managed Redis host/container에 같은 값을 반영해야 합니다.

# AWS EC2 Observability Stack (Grafana + Prometheus)

## 1. 목적

이 문서는 BIKE의 실제 AWS 운영 환경에서 **observability 전용 EC2 1대**를 추가해,
Prometheus + Grafana + exporter로 **운영 앱 내부지표를 브라우저에서 관제**하는 기준을 정리한다.

이 문서의 기본 가정은 아래와 같다.

- app EC2: Spring Boot API
- db EC2: PostgreSQL
- observability EC2: Prometheus + Grafana + exporter + nginx
- CloudWatch: 기존 인프라 로그/알람 축 유지

## 2. 왜 observability 전용 EC2를 쓰는가

- app EC2에 Grafana/Prometheus를 같이 올리면 앱과 관제 스택이 자원을 경쟁한다.
- Grafana 브라우저 공개를 app EC2와 같은 서버에서 처리하면 보안 경계가 흐려진다.
- observability 전용 EC2를 두면 app/db는 private target으로만 남기고, 브라우저 공개는 Grafana reverse proxy 한 지점으로 고정할 수 있다.

즉 현재 BIKE 기준 기본 추천안은 **전용 observability EC2 1대**다.

## 3. 현재 목표 도메인

- 운영 관제 도메인: `observability.gajabike.shop`
- 가비아 DNS에는 `observability` 서브도메인을 observability EC2 public IP로 연결한다.

## 4. 네트워크 구조

### 3-1. 외부 공개

- 인터넷 -> `https://observability.gajabike.shop` -> observability EC2 nginx -> Grafana

### 3-2. private scrape 경로

- Prometheus -> `app EC2:18081/actuator/prometheus`
- Prometheus -> local `postgres_exporter:9187`
- Prometheus -> local `redis_exporter:9121`
- `postgres_exporter` -> `db EC2:5432`
- `redis_exporter` -> `REDIS_URL` 대상

즉 browser는 Grafana만 보고, 내부 scrape는 private IP/SG로 제한한다.

## 5. 보안 그룹 기준

### app EC2

- `8080`은 기존 API 공개 정책 유지
- `18081`은 **observability EC2 SG에서만 허용**

### db EC2

- `5432`는 app EC2 SG + observability EC2 SG만 허용

### Redis 대상

- Redis port는 app backend와 observability EC2에서만 허용

### observability EC2

- `443`만 public 허용
- `80`은 인증서 발급/redirect 용도만 허용 가능
- `3000`, `9090`, `9187`, `9121`은 외부 공개하지 않음

## 6. app EC2 반영 사항

Spring Boot에는 이미 아래가 들어가 있다.

- `/actuator/prometheus`
- custom business metric
- management port `18081`

운영에서는 아래가 추가로 필요하다.

- app `.env`에 `MANAGEMENT_PORT=18081`
- security group으로 `18081`을 observability EC2에서만 허용
- 필요 시 `MANAGEMENT_SERVER_ADDRESS=0.0.0.0`

## 7. observability EC2 파일 위치

운영용 배포 파일은 아래 경로를 기준으로 둔다.

- compose: `ops/observability/ec2/docker-compose.observability.yml`
- env 예시: `ops/observability/ec2/.env.example`
- Prometheus template: `ops/observability/ec2/prometheus/prometheus.yml.template`
- render script: `ops/observability/ec2/render-prometheus-config.sh`
- start script: `ops/observability/ec2/start-observability-stack.sh`
- validate script: `ops/observability/ec2/validate-observability-stack.sh`
- nginx sample: `ops/observability/ec2/nginx/observability.conf`
- systemd sample: `ops/observability/ec2/systemd/bike-observability.service`

## 8. 배포 순서

### 7-1. observability EC2 준비

1. Docker / Docker Compose 설치
2. nginx 설치
3. `/opt/bike-observability` 디렉터리 생성
4. 위 파일들을 복사
5. `.env` 작성

임시로 public IP 기반 검증만 먼저 할 때는 아래처럼 둘 수 있다.

- `GRAFANA_DOMAIN=<observability-public-ip>`
- `GRAFANA_ROOT_URL=http://<observability-public-ip>:3000`

### 7-2. app EC2 준비

1. backend env에 `MANAGEMENT_PORT=18081` 반영
2. app 재기동
3. observability EC2에서 `curl http://<app-private-ip>:18081/actuator/prometheus` 확인

### 7-3. stack 기동

```bash
cd /opt/bike-observability
./render-prometheus-config.sh ./.env
docker compose --env-file ./.env -f docker-compose.observability.yml up -d
```

### 8-0. 가비아 DNS 선행 작업

가비아에는 아래 레코드를 먼저 추가한다.

- 타입: `A`
- 호스트: `observability`
- 값: observability EC2 public IP

현재 기준 observability EC2 public IP는 운영 반영 시점에 다시 확인한다.

### 8-4. reverse proxy 연결

1. nginx conf 반영
2. TLS 인증서 연결
3. basic auth 또는 SSO 적용

## 9. 검증 순서

### 8-1. private target 검증

- app endpoint: `curl http://<app-private-ip>:18081/actuator/prometheus`
- postgres exporter: `curl http://127.0.0.1:9187/metrics`
- redis exporter: `curl http://127.0.0.1:9121/metrics`

### 8-2. Prometheus 검증

- `curl http://127.0.0.1:9090/-/ready`
- `curl http://127.0.0.1:9090/api/v1/targets`

### 9-3. Grafana 검증

- `http://127.0.0.1:3000/login`
- 브라우저에서 `https://observability.gajabike.shop` 접속
- `BIKE API Overview`
- `BIKE Data Platform Overview`
- `BIKE Load Validation Prep`

## 10. 브라우저 접근 경로

사용자 브라우저 접근 경로는 아래 한 줄로 정리한다.

> `https://observability.gajabike.shop` -> nginx -> Grafana

즉 브라우저는 AWS 콘솔이 아니라, 별도 observability 주소로 Grafana를 본다.

## 11. 지금 하지 않는 것

- CI/CD로 observability EC2 자동 배포
- Loki/Tempo 즉시 도입
- Prometheus/Grafana public direct exposure
- app EC2와 observability stack의 단일 서버 공존

## 12. 운영 메모

- Prometheus/Grafana는 먼저 수동 배포로 안정화하고, 이후 필요 시 SSM/Actions 자동화로 확장한다.
- CloudWatch는 계속 인프라 로그/알람 원본으로 유지한다.
- Grafana는 운영자 read surface다.

## 13. 가비아 DNS 입력값 메모

- 레코드명: `observability`
- 레코드 타입: `A`
- 대상 값: observability EC2 public IP
- TTL: 가비아 기본값 또는 300초

## 14. 변경 이력

- 2026-04-24: 실제 운영 도메인 `observability.gajabike.shop` 기준으로 DNS와 브라우저 접근 경로를 구체화했다.
- 2026-04-24: observability 전용 EC2 + Prometheus + Grafana + exporter + nginx reverse proxy 기준을 추가했다.

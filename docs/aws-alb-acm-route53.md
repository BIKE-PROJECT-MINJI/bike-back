# AWS ALB + ACM + DNS 전환 초안

## 목적

이 문서는 현재 `bike-back`의 **EC2 + systemd + SSM** 배포 구조 앞단에 **ALB + ACM 인증서**를 추가해,
앱 공개 경로를 `HTTP public IP` 에서 `HTTPS 도메인` 으로 전환하는 초안을 정리한다.

현재 저장소 기준 backend 공개 접근은 다음에 가깝다.

- backend app: `Spring Boot` on app EC2
- deploy: `GitHub Actions -> OIDC -> S3 -> SSM -> systemd restart`
- frontend base URL: 현재 배포 backend 기준 `http://3.35.168.38`

즉 이번 변경의 핵심은 **배포 방식 변경이 아니라 API 진입점 변경**이다.

## 현재 구조

- Android 앱이 backend 공인 IP로 직접 접근한다.
- TLS 종료 지점이 앱 공개 API 앞단에 없다.
- observability는 별도 nginx + certbot 흐름이 있지만, app API 앞단은 아직 ALB 기반이 아니다.

## 목표 구조

```text
Android App
  -> https://api.gajabike.shop
  -> ALB (80 redirect / 443 terminate TLS)
  -> app EC2 :8080 (Spring Boot)
  -> PostgreSQL / Redis
```

## 왜 ALB를 두는가

- 앱 공개 API를 `HTTPS` 로 고정할 수 있다.
- Android release URL을 공인 IP 대신 도메인으로 관리할 수 있다.
- 인증서 갱신을 EC2 내부 certbot 흐름 대신 ACM으로 옮길 수 있다.
- 이후 다중 인스턴스, WAF, access log 같은 edge 확장이 쉬워진다.

## 최소 리소스

- ACM public certificate
- internet-facing ALB
- HTTP listener (80 -> 443 redirect)
- HTTPS listener (443 -> target group forward)
- EC2 instance target group
- ALB security group
- app instance security group rule (source = ALB SG)
- DNS record

## DNS 전략

### 현재 기준: 가비아 유지

현재 문서에는 `gajabike.shop` DNS를 **가비아** 에서 관리하는 흐름이 이미 있다.
그래서 1차 적용은 아래가 더 현실적이다.

1. ACM certificate를 `DNS validation` 으로 요청
2. Terraform output 또는 AWS 콘솔에서 validation CNAME 확인
3. 가비아 DNS에 validation CNAME 수동 등록
4. 인증서 발급 완료 후 HTTPS listener를 다시 apply
5. `api.gajabike.shop` 을 ALB로 연결

즉 **ALB/ACM은 AWS**, **DNS record 입력은 가비아** 로 나뉜다.

가비아 작업자가 실제로 입력해야 하는 값만 보려면 아래 문서를 본다.

- `docs/gabia-dns-alb-acm-guide.md`

### 선택지: Route53으로 이동

만약 이후 DNS를 Route53으로 옮기면,
같은 Terraform 초안에서 validation record와 alias record까지 함께 자동화할 수 있다.

## 저장소 반영 위치

- Terraform 초안: `ops/aws/alb-acm-route53/`
- 설명 문서: `docs/aws-alb-acm-route53.md`
- 가비아 입력 가이드: `docs/gabia-dns-alb-acm-guide.md`

현재 저장소는 `ops/<capability>/<env>` 와 `docs/aws-*.md` 패턴을 쓰고 있어서,
이 위치가 기존 구조와 가장 잘 맞는다.

## 적용 후 같이 바꿔야 하는 값

### 1. GitHub Actions

`backend-cd.yml` 에서 public smoke check는 `HEALTHCHECK_URL` 변수를 사용한다.

현재:
- `http://<public-ip>/health`

전환 후:
- `https://api.gajabike.shop/health`

### 2. Android release base URL

현재 `bike-front` README에는 배포 backend 기준 URL이 `http://3.35.168.38` 로 적혀 있다.
ALB 전환 후에는 release build URL을 다음처럼 바꾸는 것이 목표다.

- `https://api.gajabike.shop`

### 3. app EC2 security group

- `8080` 인바운드는 인터넷 전체 허용 대신 **ALB security group source only** 로 좁힌다.
- public direct access를 끊고 ALB 경유만 허용하는 쪽이 맞다.

## 이번 초안에서 하지 않는 것

- ECS/EKS 전환
- multi-app blue/green 배포
- WAF 자동화
- Route53 강제 이전
- DB/Redis 구조 변경

## 권장 적용 순서

1. ALB + target group + security group 생성
2. ACM certificate 요청
3. 가비아 DNS에 validation CNAME 등록
4. ACM이 `ISSUED` 상태인지 확인
5. HTTPS listener + HTTP redirect 재적용
6. `api.gajabike.shop` 을 ALB로 연결
7. `https://api.gajabike.shop/health` 확인
8. GitHub Actions `HEALTHCHECK_URL` 변경
9. Android release base URL 변경

## 운영 메모

- 현재 구조는 단일 app EC2 target이라 ALB를 붙여도 **backend 자체 SPOF는 그대로** 다.
- 다만 HTTPS 종단, 도메인 진입, SG 축소, 이후 확장성 면에서는 큰 개선이다.
- observability 도메인에서 사용하던 certbot/nginx 방식과 app API 앞단 ALB 방식은 병행 가능하다.

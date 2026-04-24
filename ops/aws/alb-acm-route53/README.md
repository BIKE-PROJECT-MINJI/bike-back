# AWS ALB + ACM + DNS draft

이 디렉터리는 `bike-back` 앞단에 **Application Load Balancer + ACM 인증서**를 두기 위한 Terraform 초안이다.

## 목적

- 현재 `EC2 + systemd + SSM` 구조는 유지한다.
- 앱 공개 진입점을 `http://<public-ip>` 에서 `https://api.gajabike.shop` 같은 도메인으로 바꾼다.
- TLS 종료는 EC2 nginx가 아니라 **ALB** 에서 처리한다.

## 포함 리소스

- public ALB
- ALB security group
- backend EC2 security group ingress rule (ALB source only)
- instance target group
- HTTP 80 -> HTTPS 443 redirect listener
- HTTPS 443 forward listener
- ACM public certificate (`DNS` validation)
- optional Route53 validation/alias record

## DNS 운영 방식

### 1) 현재처럼 가비아 DNS를 유지하는 경우

- `create_route53_records = false`
- `enable_https_listener = false` 로 1차 apply
- `terraform apply` 후 `certificate_validation_records` output을 확인한다.
- 출력된 ACM validation CNAME을 가비아 DNS에 수동 등록한다.
- 인증서가 `ISSUED` 되면 `enable_https_listener = true` 로 바꾸고 다시 apply 한다.
- 그 뒤 가비아에서 `api` 서브도메인을 ALB DNS name 기준으로 연결한다.
  - 운영 시 DNS provider 제약 때문에 `A/ALIAS` 또는 `CNAME` 구성은 실제 가비아 UI 지원 방식에 맞춘다.

### 2) Route53으로 옮기는 경우

- `create_route53_records = true`
- 인증서 검증이 자동화되면 `enable_https_listener = true` 로 바로 운영값을 둘 수 있다.
- `route53_hosted_zone_id` 입력
- Terraform이 ACM validation record와 ALB alias record를 같이 만든다.

## 적용 순서

1. `terraform.tfvars.example` 를 복사해 실제 값 입력
2. `terraform init`
3. `terraform plan`
4. `terraform apply`
5. DNS validation 완료 확인
6. `enable_https_listener = true` 로 재적용
7. backend smoke URL을 `https://api.gajabike.shop/health` 로 전환
8. Android release build의 API base URL을 HTTPS 도메인으로 변경

## 후속 반영 포인트

- GitHub Actions `HEALTHCHECK_URL` 변수 변경
- Android `releaseApiBaseUrl` 변경
- 필요 시 ALB access log/S3, WAF, blue-green target group 추가

## 주의

- 이 초안은 **단일 app EC2** 를 ALB target으로 쓰는 현재 구조 기준이다.
- DB/Redis/observability 구조는 바꾸지 않는다.
- 가비아 DNS를 계속 쓸 경우 Route53 alias 자동화는 적용되지 않는다.
- 가비아 수동 DNS 흐름에서는 보통 **2단계 apply** 가 필요하다.

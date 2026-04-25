# ALB + ACM + Gabia 적용 체크리스트

이 체크리스트는 `/mnt/c/Users/alswl/Desktop/bike/dev/bike-back/ops/aws/alb-acm-route53/terraform.tfvars.discovered` 기준이다.

## 0. 현재 확인된 실제 값

- region: `ap-northeast-2`
- app instance: `i-0e5bce97db12b2300` (`bike-back-app`)
- current public IP: `3.35.168.38`
- VPC: `vpc-0602381a2cb0a950e`
- app SG: `sg-0d7393e764b0f7135`
- public subnets:
  - `subnet-056cda04cc8cbd0cf` (`ap-northeast-2a`)
  - `subnet-0cb2f3e103f6a8b46` (`ap-northeast-2c`)
  - `subnet-0335ced9c5df2f144` (`ap-northeast-2b`)
  - `subnet-0a34bc7630bfdd7bc` (`ap-northeast-2d`)

## 1. 1차 apply

```bash
cd /mnt/c/Users/alswl/Desktop/bike/dev/bike-back/ops/aws/alb-acm-route53
terraform init
terraform plan -var-file=terraform.tfvars.discovered
terraform apply -var-file=terraform.tfvars.discovered
```

1차 apply 목적:

- ALB 생성
- target group 생성
- ACM certificate 요청
- HTTP 80 listener만 생성
- 아직 HTTPS listener는 만들지 않음

## 2. 가비아 DNS에 ACM validation CNAME 등록

가비아 화면에서 실제로 무엇을 넣는지는 아래 문서를 같이 본다.

- `/mnt/c/Users/alswl/Desktop/bike/dev/bike-back/docs/gabia-dns-alb-acm-guide.md`

아래 명령으로 ACM 검증용 레코드를 확인한다.

```bash
terraform output -json certificate_validation_records
```

출력 예시 구조:

```json
[
  {
    "domain_name": "api.gajabike.shop",
    "name": "_xxxx.api.gajabike.shop.",
    "type": "CNAME",
    "value": "_yyyy.acm-validations.aws."
  }
]
```

가비아 DNS에서 위 값을 그대로 CNAME으로 등록한다.

## 3. ACM 발급 완료 확인

AWS 콘솔 또는 CLI에서 인증서가 `ISSUED` 상태인지 확인한다.

예시:

```bash
aws acm list-certificates --region ap-northeast-2
```

## 4. 2차 apply

`terraform.tfvars.discovered` 에서 아래 값만 바꾼다.

```hcl
enable_https_listener = true
```

그 다음 다시 적용한다.

```bash
terraform plan -var-file=terraform.tfvars.discovered
terraform apply -var-file=terraform.tfvars.discovered
```

2차 apply 목적:

- HTTPS 443 listener 생성
- HTTP 80 -> HTTPS 443 redirect 적용

## 5. 가비아에 API 도메인 연결

ALB DNS 이름을 확인한다.

```bash
terraform output alb_dns_name
```

가비아에서 `api.gajabike.shop` 을 위 ALB DNS 이름으로 연결한다.

- 가비아 UI가 ALIAS/A 지원을 하면 그 방식 우선
- 아니라면 provider가 허용하는 방식으로 `CNAME` 사용 여부 확인

## 6. 동작 확인

```bash
curl -I http://api.gajabike.shop
curl -I https://api.gajabike.shop/health
curl -fsS https://api.gajabike.shop/health
```

정상 기대값:

- `http://api.gajabike.shop` -> `301` or `302` redirect to HTTPS
- `https://api.gajabike.shop/health` -> `200`

## 7. 후속 변경

### GitHub Actions

GitHub Variables의 `HEALTHCHECK_URL` 을 다음으로 바꾼다.

```text
https://api.gajabike.shop/health
```

### Android release base URL

현재 README 기준 배포 backend URL은 `http://3.35.168.38` 이다.
release build 기준값을 아래로 전환한다.

```text
https://api.gajabike.shop
```

## 8. 적용 직후 보안 정리

현재 app SG `sg-0d7393e764b0f7135` 는 `80`, `443` 이 public open 상태다.

ALB 전환 후에는 app instance SG에서:

- `8080` 은 ALB SG source only 허용
- 기존 public `80`, `443` open rule 제거 검토

즉 최종 형태는 **인터넷 -> ALB -> app EC2:8080** 이어야 한다.

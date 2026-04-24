# 가비아 DNS 작업 가이드

이 문서는 **가비아 DNS에서 무엇을 입력해야 하는지**만 따로 정리한 문서다.
AWS ALB/ACM/Terraform 자체 설명은 `docs/aws-alb-acm-route53.md` 와
`ops/aws/alb-acm-route53/APPLY_GABIA_CHECKLIST.md` 를 따른다.

## 지금 가비아에서 해야 하는 일

가비아에서 해야 할 일은 딱 2가지다.

1. **ACM 인증서 검증용 CNAME 등록**
2. **`api.gajabike.shop` 을 ALB 쪽으로 연결**

즉 가비아는 **DNS 입력 담당**이고,
ALB/ACM 생성 자체는 AWS/Terraform 쪽 작업이다.

---

## 1. ACM 인증서 검증용 CNAME 등록

Terraform 1차 apply 후 아래 명령으로 값을 확인한다.

```bash
cd /mnt/c/Users/alswl/Desktop/bike/dev/bike-back/ops/aws/alb-acm-route53
terraform output -json certificate_validation_records
```

예시 출력:

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

가비아 DNS 관리 화면에서 위 값을 그대로 넣는다.

### 가비아에 넣는 값

- 레코드 타입: `CNAME`
- 호스트/이름: Terraform output의 `name`
- 값/대상: Terraform output의 `value`
- TTL: 가비아 기본값 또는 `300`

### 주의

- 끝의 `.` 이 붙은 값이 나오면 **가비아 UI 입력 방식에 맞춰** 넣는다.
- 중요한 건 문자열을 임의로 바꾸지 않는 것이다.
- 이 CNAME이 들어가야 ACM 인증서가 `ISSUED` 상태가 된다.

---

## 2. `api.gajabike.shop` 을 ALB로 연결

ACM이 `ISSUED` 된 뒤 2차 apply를 마치면,
아래 명령으로 ALB DNS 이름을 확인한다.

```bash
cd /mnt/c/Users/alswl/Desktop/bike/dev/bike-back/ops/aws/alb-acm-route53
terraform output alb_dns_name
```

예시 출력:

```text
bike-api-alb-123456789.ap-northeast-2.elb.amazonaws.com
```

이제 가비아에서 `api.gajabike.shop` 을 위 ALB DNS 이름으로 연결한다.

### 가비아에 넣는 값

- 서브도메인/호스트: `api`
- 대상 값: `terraform output alb_dns_name` 결과값

### 레코드 방식

- 가비아 UI에서 `CNAME` 이 허용되면 `api` 서브도메인에 CNAME으로 연결
- 가비아가 ALIAS/ANAME 유사 기능을 지원하면 그 방식 사용 가능
- 현재 기준은 **`api` 같은 서브도메인이므로 CNAME 연결이 가장 현실적**이다

---

## 입력 순서

순서는 반드시 아래대로 간다.

1. Terraform 1차 apply
2. 가비아에 **ACM validation CNAME** 등록
3. ACM `ISSUED` 확인
4. Terraform 2차 apply (`enable_https_listener = true`)
5. 가비아에 **`api.gajabike.shop` -> ALB DNS** 연결

즉 `api` 레코드를 먼저 연결하는 게 아니라,
**ACM 검증 CNAME이 먼저**다.

---

## 가비아 작업 후 확인할 것

```bash
curl -I http://api.gajabike.shop
curl -I https://api.gajabike.shop/health
curl -fsS https://api.gajabike.shop/health
```

정상 기대값:

- `http://api.gajabike.shop` -> HTTPS redirect
- `https://api.gajabike.shop/health` -> `200`

---

## 한 줄 요약

가비아에서는 **ACM 검증용 CNAME 1개** 넣고,
그 다음 **`api.gajabike.shop` 을 ALB DNS로 연결하는 레코드 1개** 넣으면 된다.

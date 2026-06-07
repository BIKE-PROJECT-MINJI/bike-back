# AWS EC2 compose + k6 100명 부하테스트

## 목적

임시 EC2에서 테스트 compose 스택을 올리고, 같은 인스턴스 내부에서 k6를 실행해 외부 공개면 없이 백엔드 API 성능을 확인한다.

대상 흐름:

- AI 코스 생성: `POST /api/v1/ai-routes/plan/from-text`
- 자유주행 저장/재처리: `POST /api/v1/ride-records`, `POST /api/v1/ride-records/{id}/regenerate`
- 코스 따라가기 읽기: 주행 기록 기반 코스 생성, 코스 상세/route-points 조회, ride-policy 평가

## 실행 파일

- k6 script: `ops/loadtest/k6/ai-route-graphhopper-100-users.js`
- env example: `ops/loadtest/k6.ai-route-100.env.example`

기본 VU 배분은 34 + 33 + 33 = 100명이다.

## 실행

```bash
mkdir -p ops/loadtest/results
k6 run \
  -e BASE_URL=http://127.0.0.1:8080 \
  -e TEST_ID=bike-ai-route-aws-20260607 \
  -e SUMMARY_PATH=ops/loadtest/results/bike-ai-route-aws-20260607-summary.json \
  ops/loadtest/k6/ai-route-graphhopper-100-users.js
```

## 결과 기준

k6 summary JSON에서 다음 값을 보고한다.

- 전체 `http_req_failed.rate`
- 전체 `http_req_duration` p95/p99
- `flow:ai-route`, `flow:free-ride`, `flow:course-follow`별 p95/p99
- checks rate

테스트 threshold는 AI/GraphHopper cold latency를 고려해 AI route p95 60초, p99 90초로 시작한다. 결과가 안정되면 이 값을 낮추는 것을 다음 개선 과제로 삼는다.

## 비용 안전

테스트 리소스는 `bike-ulw-loadtest-20260607` prefix/tag로 만든다. 테스트 후 다음 리소스가 남지 않았음을 증거로 남긴다.

- EC2 instance: terminated
- EBS volume: DeleteOnTermination 또는 삭제 완료
- security group: deleted
- key pair: deleted
- local private key/temp bundle: removed

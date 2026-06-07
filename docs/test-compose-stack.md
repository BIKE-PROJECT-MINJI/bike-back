# 테스트용 전체 Docker Compose 스택

## 목적

로컬 테스트에서 백엔드와 의존 서비스를 따로 켜지 않도록 `docker-compose.test.yml` 하나로 묶는다.

포함 서비스:

- PostgreSQL/PostGIS
- Redis
- GraphHopper asset prepare
- self-host GraphHopper
- AI route worker
- Spring Boot backend

## 실행

8080은 사용자 로컬 테스트 고정 포트다. 이미 일반 Java 서버가 8080을 점유 중이면 종료한 뒤 실행한다.

```bash
cd dev/bike-back
cp .env.test.example .env.test
# .env.test의 AUTH_JWT_SECRET은 32바이트 이상 개인 테스트 값으로 바꾼다.
./gradlew clean bootJar -x test -Pproduction
docker compose --env-file .env.test -f docker-compose.test.yml up --build
```

백그라운드 실행:

```bash
./gradlew clean bootJar -x test -Pproduction
docker compose --env-file .env.test -f docker-compose.test.yml up --build -d
```

처음 실행할 때는 GraphHopper jar와 South Korea OSM PBF를 받아오고 graph-cache를 import하므로 오래 걸릴 수 있다. 이후 실행은 named volume의 cache를 재사용한다.

## 확인

```bash
curl -sS http://127.0.0.1:8080/health
curl -sS http://127.0.0.1:8080/health/monitor
```

경로 생성 API는 backend 내부에서 다음 주소를 사용한다.

- AI worker: `http://ai-route-worker:8091`
- GraphHopper: `http://graphhopper:8989`

따라서 host의 8989/8091 포트가 이미 사용 중이어도 이 테스트 스택과 충돌하지 않는다.

## 정리

컨테이너만 내리기:

```bash
docker compose --env-file .env.test -f docker-compose.test.yml down
```

테스트 DB와 GraphHopper cache까지 초기화:

```bash
docker compose --env-file .env.test -f docker-compose.test.yml down -v
```

## 정책

- 테스트 compose는 깨끗한 named volume을 사용하므로 기존 로컬 DB의 Flyway checksum drift와 분리된다.
- 테스트 compose는 `8080`, `18081`을 `127.0.0.1`에만 바인딩한다.
- `AUTH_JWT_SECRET`은 compose 기본값을 두지 않고 `.env.test`에서 명시한다.
- `bike-back` 테스트 이미지는 Docker 내부 Gradle 빌드가 아니라 로컬 `bootJar` 산출물을 복사한다.
- GraphHopper self-host는 key 없이 호출한다.
- AI worker key는 worker 환경변수로만 주입한다.
- Android/RN 클라이언트는 GraphHopper/AI provider key를 알지 않는다.

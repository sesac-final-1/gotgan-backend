# 곳간 GOTGAN — backend

> **소상공인을 위한 스마트스토어형 오픈마켓** — 작은 가게들의 큰 장터

판매자가 **입점 신청 → 관리자 승인 → 자기 스토어 운영**을 하고, 구매자는 여러 스토어의 상품을 한 번에 사고, 운영자는 스토어별 판매대금을 **수수료를 제외하고 정산**합니다.
이커머스의 핵심(상품 · 주문 · 결제)을 DDD로 쪼개 클라우드 네이티브(EKS)로 올린 **"안 죽는 MSA"** 를 목표로 합니다 — 서비스 하나가 죽어도 나머지는 정상, 무중단 배포, 자동 복구를 시연합니다.

| | 링크 |
|---|---|
| 📘 설계 문서 (가이드라인 · 스펙 · 컨벤션) | https://gotganguide.vercel.app/ |
| 🎨 화면 목업 (42개 화면) | https://mockup-page-sable.vercel.app/ |
| 📦 저장소 3개 | **[gotgan-backend](https://github.com/sesac-final-1/gotgan-backend)** (이 저장소) · [gotgan-frontend](https://github.com/sesac-final-1/gotgan-frontend) · [gotgan-config](https://github.com/sesac-final-1/gotgan-config) |

이 저장소는 **Spring Boot 서비스 9개 + 서버리스 함수 3개**입니다. 배포 매니페스트 · Terraform · 운영 문서는 `gotgan-config`, React 앱은 `gotgan-frontend`에 있습니다.

---

## 팀 gojigi

| 이름 | GitHub | 인프라 | 서비스 · 도메인 |
|---|---|---|---|
| 구지훈 | [@jihoon-devstu](https://github.com/jihoon-devstu) | AWS IaC (Terraform) · 일일 스케줄 · 시크릿 동기화 · SonarQube Cloud | `settlement` · `admin` — 스마트스토어 관리자 도메인 · 알림 · 명세서 Lambda |
| 이경석 | [@perish95](https://github.com/perish95) | CI/CD (GitHub Actions · ArgoCD) · Keycloak | `gateway` · `user` — 회원 · 인증 · 인가 |
| 박지연 | [@VoC-100](https://github.com/VoC-100) | 로깅 · 관측성 (Prometheus · Loki · Grafana · Zipkin) · 헬스 체크 대시보드 | `product` · `store` — 판매자 스토어 도메인 · 로그 아카이브 Lambda |
| 이종빈 | [@jongbeen97](https://github.com/jongbeen97) | — | `order` · `payment` · `review` — 구매 흐름 |

- 기간 **2026-10-12 ~ 11-20** · **1~2주차 인프라 → 3~5주차 도메인 개발(프론트 포함) → 6주차 QA · 발표** · 개발은 **11-13(금) 기능 동결** · **11-20 발표**
- 1~3일 스프린트 + 매주 금요일 중간 점검, 초반 데일리 스크럼

---

## 기술 스택

| 영역 | 기술 |
|---|---|
| 백엔드 | Java 21 · Spring Boot 4.1 · Spring Cloud Gateway · Spring Data JPA · QueryDSL · Flyway · Resilience4j · springdoc |
| 인증 | Keycloak 26 (realm `gotgan`, 클러스터 내부 전용) — 우리 로그인 페이지 → user-service가 대리 호출 |
| 데이터 | MySQL 8.4 (1대 · 서비스별 스키마 8개 · 계정 분리) · Redis 7 (장바구니 캐시, 원본은 MySQL) · DynamoDB (Lambda 멱등 · 이력) · S3 |
| 메시징 | SNS → SQS (+DLQ) 도메인 이벤트 · 주문 Saga는 동기 REST 오케스트레이션 + 보상 |
| 서버리스 | Serverless Framework v4 · Lambda (배치 · 로깅만: 알림 메일 · 정산 명세서 · 로그 아카이브) |
| 결제 | 포트원(PortOne) V2 테스트 채널 |
| 인프라 · CI (gotgan-config) | AWS EKS (t3.large × 2, 매일 08:00 생성 · 22:00 삭제) · Terraform · GitHub Actions (OIDC) · ArgoCD · SonarQube Cloud · Prometheus · Loki · Zipkin · Grafana |

---

## 서비스

| 서비스 | 포트 | 스키마 | 역할 | 담당 |
|---|---|---|---|---|
| `gateway-service` | 8080 | — | 라우팅 · Keycloak JWT 검증 · 사용자 헤더 주입 · CORS | 이경석 |
| `user-service` | 8081 | `userdb` | 회원 · 가입/로그인(Keycloak 대리) · 배송지 · 포인트 · 등급 | 이경석 |
| `store-service` | 8082 | `storedb` | 입점 신청 · 심사 · 스토어 · 판매자 대시보드(조합) | 박지연 |
| `product-service` | 8083 | `productdb` | 카테고리 · 상품 · 옵션 · 재고 · 이미지 · QueryDSL 검색 | 박지연 |
| `order-service` | 8084 | `orderdb` + Redis | 장바구니 · 주문 · 배송(status) · 취소/반품/교환 · **Saga 오케스트레이터** | 이종빈 |
| `settlement-service` | 8085 | `settlementdb` | 정산 적재 · 주간 집계 · 지급 · 카테고리별 수수료 | 구지훈 |
| `payment-service` | 8086 | `paymentdb` | 결제 · 포트원 V2 검증/취소 · 웹훅 | 이종빈 |
| `review-service` | 8087 | `reviewdb` | 리뷰 · 판매자 답글 · 상품문의 · 리뷰 작성 자격 · 관리자 블라인드 | 이종빈 |
| `admin-service` | 8088 | — | 관리자 API 진입점 · `/internal/**` 조합 · 관리자 대시보드 | 구지훈 |

규칙 요약: 모든 응답은 `ApiResponse<T>` 봉투 `{ code, message, data }` · 에러 코드는 의미형(`ORDER_OUT_OF_STOCK`) · 시간대는 DB · JDBC · JVM 모두 Asia/Seoul(`created_at` · `updated_at`은 DB가 기록) · 다른 서비스 DB에 FK · JOIN 금지 · 관리자 기능은 전부 `/api/admin/**` → admin-service → 각 서비스 `/internal/**` · 모든 동기 호출은 Resilience4j. 전체 규칙은 [docs/CONVENTION.md](docs/CONVENTION.md).

---

## 구조

```
gotgan-backend/
├── services/{name}-service/     독립 Gradle 프로젝트 1개 = 이미지 1개 = Deployment 1개
│   ├── build.gradle · Dockerfile · gradle.properties(공통 버전)
│   └── src/main/java/com/sesac/gojigi/{name}/
│       ├── common/              ApiResponse · 예외 · @CurrentUser · BaseTimeEntity · RestClient 설정 (서비스 템플릿, 9개 동일)
│       └── {domain}/            controller · service · repository · entity · dto · exception · client
├── serverless/                  order-notification · settlement-report · log-archive
├── docs/api/                    OpenAPI 명세 · 에러 코드 목록
├── docs/CONVENTION.md           백엔드 · DB · 로깅 컨벤션 (문서 사이트 발췌본)
├── docker-compose.yml           로컬 MySQL(스키마 8) + Redis + Keycloak — gotgan-config의 스크립트 · realm을 마운트
└── .github/workflows/           backend(테스트 → Sonar → ECR → config 태그 커밋) · serverless · security
```

---

## 빠른 시작

```bash
# 0. 저장소 세 개를 같은 폴더에 나란히 clone (docker-compose가 ../gotgan-config 를 참조)
#    gotgan/gotgan-backend · gotgan/gotgan-config · gotgan/gotgan-frontend

# 1. 로컬 인프라 — MySQL(스키마 8개 · 계정 자동 생성) · Redis · Keycloak(realm 자동 import)
docker compose up -d
#    Keycloak 콘솔 http://localhost:8180 (admin / admin)

# 2. 자기 서비스만 local 프로필로 실행 (9개를 다 띄우면 노트북이 버티지 못함)
cd services/order-service
./gradlew bootRun --args='--spring.profiles.active=local'
#    Swagger  http://localhost:8084/swagger-ui.html   ·   health http://localhost:8084/actuator/health

# 3. 게이트웨이를 거쳐 호출하려면 gateway-service 도 실행 (:8080)
```

- 스키마 · 계정은 `gotgan-config/deploy/k8s/db/init-schemas.sh`가 만들고(빈 볼륨으로 처음 뜰 때 1회), 테이블은 각 서비스의 Flyway(`db/migration`)가 만듭니다. 스크립트를 바꿨다면 `docker compose down -v`.
- 다른 서비스가 필요하면 dev 클러스터로 `kubectl port-forward svc/product-service 8083:8083 -n sesac-gojigi`, 또는 클라이언트 호출이 실패해도 폴백으로 동작하는지 확인하는 기회로 씁니다.
- dev 클러스터는 **매일 08:00 생성 · 22:00 삭제**됩니다(gotgan-config `infra-schedule`). 아침마다 DB는 Flyway 시드로 새로 시작합니다.
- Windows는 WSL 안에서 작업하세요. `.gitattributes`가 `gradlew` · `*.sh`를 LF로 고정합니다.

---

## 개발 규칙 (요약 — 전체는 [docs/CONVENTION.md](docs/CONVENTION.md))

| 항목 | 규칙 |
|---|---|
| 브랜치 | `main` 보호 (PR + 승인 1 + CI 통과 → Squash). 작업 브랜치 `feat/{도메인}-{작업}` · `fix/` · `docs/` · `chore/` |
| 커밋 | Conventional Commits `feat(order): 포트원 결제 검증 API` — 한국어 명사형, 50자 이내 |
| 이슈 · PR | 이슈 `[ORD-06] 결제 검증` · PR 본문 템플릿 · 다른 서비스에 영향 있으면 제목에 `[영향]` · 300줄 이하 |
| 소유권 | 다른 사람의 서비스는 PR 없이 수정 금지 (CODEOWNERS 자동 리뷰어) |
| 시크릿 | 코드 · YAML · 로그 · Git 히스토리 어디에도 실제 값 금지 — **GitHub Secrets** 단일 소스, 목록은 `gotgan-config/docs/secrets.md` |
| 품질 | main 대상 PR은 SonarQube Cloud 품질 게이트 통과. SQL은 파라미터 바인딩만, 사용자 HTML은 Sanitizer |

---

## 지금 상태

**뼈대 단계**입니다. 빌드 파일 · 공통 코드 · 설정 · Flyway 폴더까지 있고 도메인 코드는 각 담당자가 채웁니다. `TODO {이름}` 주석과 각 도메인의 `package-info.java`가 시작점입니다. 주차별 계획은 [가이드라인 16장](https://gotganguide.vercel.app/guideline#s13).

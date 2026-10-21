# serverless (배치 · 로깅만 — 사용자 API 없음)

| 함수 | 트리거 | 쓰는 자원 | 담당 | 우선순위 |
|---|---|---|---|---|
| `order-notification` | SQS notification-order/store-events (**10/21 구현 · 연결은 꺼 둠** — order · store 발행 시작 때 켬) | SES 메일 — 주문 확인 · 배송 시작 · 취소/반품 완료 · 입점 승인 · 반려 · 보완요청(NTF-01~04) + DynamoDB 발송 이력 · 멱등(NTF-05) | jihoon-devstu | Should |
| `settlement-report` | SQS report-settlement-events | 정산 명세서 CSV → S3 `reports/{storeId}/{week}.csv` → SES 메일(판매자 정산 화면 링크 — 다운로드는 화면에서 10분 presigned) + DynamoDB 발송 이력. 명세서 내용은 SettlementPaid payload(10/21 방식 B) | jihoon-devstu | Should |
| `log-archive` | EventBridge cron (매일 03:00 KST) | CloudWatch Logs 를 S3 log-archive 로 내보내기 (CreateExportTask) · 90일 후 Glacier | VoC-100 | Could |

- 함수 폴더 1개 = Serverless 서비스(CloudFormation 스택) 1개. 이름 `sesac-gojigi-{폴더명}`, stage `dev`, 핸들러 export 이름 `handler` (컨벤션 5-5)
- 로컬 확인: `npm run invoke:local` (events/*.json 샘플) · 테스트: `npm test`
- 배포는 GitHub Actions(serverless.yml 워크플로, OIDC 역할). 썸네일 Lambda 는 만들지 않는다 (아키텍처 문서에만)

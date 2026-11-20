# GP-LIFECYCLE-PLAY-02 — 만료 후보 Google Play 최신 재검증 안전 게이트

기준일: 2026-11-20
상태: 순수 판정 및 서버 **읽기 전용** 재검증 라이브러리. 운영 스케줄러/삭제 워커 미연결.

## 선행

- GP-LIFECYCLE-PLAY-01: 운영에 승인된 Cloud KMS 키와 구매 토큰 암호문 기록이 있어야 서버에서 Google Play `subscriptionsv2.get` 최신 상태 재조회가 가능합니다.
- 기존 GP-LIFECYCLE-DELETE: 만료된 Cloud 고객은 보존 14일 경과 후 `gallipickCloudPurgeReviews`에 `NEEDS_PLAY_RECHECK` 검토 후보만 기록합니다. 원본/구독을 삭제하지 않습니다.
- **이 PR은 새로운 Play 토큰 수집·암호화·운영 변경을 수행하지 않습니다.** 다음 서버 실행 단계에서 앞서 마련된 KMS vault와 Play GET 클라이언트를 주입하도록 만들었습니다.

## 서버 판정

`firebase/functions/cloud-play-expiry-recheck.js`:

1. Production `gallipick-production`으로 실행 환경 고정, 작업 대상은 `live` 및 `license-test` 두 운영 결제 환경만 인정, 매 스코프 최대 1~5건 조회.
2. 기존 만료 검토 문서의 UID 해시·토큰 해시·서버 만료일·보존 14일·결제 문서 revision이 현재 운영 기록과 **일치해야** 계속 진행합니다.
3. 해당 UID의 두 환경 결제 entitlement, 진행 중 회원/Cloud 삭제, 활성 수동 Cloud 지급 이용권과 7일 복원 기간을 다시 확인합니다. 다른 유효 권리가 있으면 즉시 중지합니다.
4. `*BillingTokens` 소유자 토큰 해시 연결 기록과 `*BillingTokenEscrows`의 owner/mode/tokenHash를 함께 읽습니다. KMS 암호문이 없으면 `TOKEN_ESCROW_MISSING_OR_INVALID`로 중지합니다.
5. 승인된 서버 KMS vault가 암호문을 복호화한 토큰은 **일시적으로 서버 메모리에서만 사용**해 Google Play GET 요청을 실행합니다. 토큰 문자열은 Firestore, 반환값, 관리자 웹, CI 로그·오류 상세에 저장하지 않습니다.
6. Google Play 응답의 UID 난독화 계정 식별자, 라이선스 테스트/실결제 모드, `SUBSCRIPTION_STATE_EXPIRED`, 정확한 상품·요금제와 Play의 마지막 유효 만료 시각을 검증합니다. 마지막 만료일로부터 14일이 지나지 않았거나 다른 상품으로 바뀌었다면 중지합니다.
7. 모든 조건이 충족되어도 반환 상태는 `PLAY_EXPIRED_CONFIRMED`일 뿐 `deletionAllowed=false`, `deletionExecuted=false`를 유지합니다. 실제 삭제 작업 ID를 만들지 않으며 Storage·Firestore·Authentication에는 **쓰기 동작이 없습니다**.
8. Play 또는 KMS 서비스 오류, 구매 토큰 누락, 불일치, 일부 필드 미확인은 `PLAY_RECHECK_UNAVAILABLE` 또는 더 보수적인 보류 상태로 처리합니다. 다른 사용자의 미디어를 삭제하거나 만료 날짜를 추측하지 않습니다.

## 회귀 테스트

- 서버 `cloud-play-expiry-recheck.test.js`에서 Play 실제 만료 증빙, 14일 기산, 갱신·취소·유예·보류, 다른 UID/상품, 두 결제 환경의 격리, 유료 entitlement·수동 지급권, 계정 탈퇴 상태, 암호문 누락·KMS 오류를 검사합니다.
- 서비스 조회 전후 Firestore fixture의 변경 내역을 비교해 **실행 부작용이 없는 것**을 확인합니다.
- 실제 운영 Play API 조회와 KMS 복호화는 실행하지 않습니다. 비공개 계정 구매토큰·실제 사용자 원본 데이터에 접근하지 않습니다.

## 후속 작업 및 배포 제한

1. GP-LIFECYCLE-PLAY-01 코드가 최신 main에 병합되고 관련 Cloud backend·Emulator QA가 성공했는지 확인.
2. 본 읽기 전용 상태 분류/서버 재검증 코드의 PR CI 성공 확인 및 병합.
3. 별도 GP-LIFECYCLE-PLAY-03에서 **기본 OFF인 Scheduler**를 연결할 때 KMS 환경 변수, Play Developer API 권한, KMS 키/Service Account IAM, Firebase Firestore 인덱스가 실제 준비됐는지 검증합니다. 첫 배포는 가짜 계정·KMS 합성 암호문으로 읽기 전용 QA만 시행합니다.
4. Play 최신 만료가 재확인되더라도 클라우드 실제 삭제는 **별도 PR, 운영 승인, 원본 백업·복구·사용자 고지, 재구독 경합·중복 워커 방어** 검증 전 금지합니다.
5. 기존 구매 토큰 SHA만 남은 계정은 원본 토큰이 없어 조회 불가능합니다. 사용자가 정식 구매 복구를 진행하거나 새 RTDN 구매 검증 경로에서 승인된 KMS 암호문이 생성될 때까지 자동 삭제 대상에 넣지 않습니다.
6. 최종 Android API 29/35/36 전체 계측은 기능 배치 동결 뒤 정확한 main SHA에서 1회 실행합니다. 개발 단계별 Google Play/운영 Firebase 배포는 하지 않습니다.

## 관련

- PR #160: https://github.com/hebi10/GalliPick/pull/160
- GP-LIFECYCLE 삭제 후보 검토: `firebase/functions/cloud-expiry-purge-review.js`
- 공식 Google Play 구독 상태: https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2/get

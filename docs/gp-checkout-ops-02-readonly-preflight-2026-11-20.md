# GP-CHECKOUT-OPS-02 — 신규 고객 결제 운영 준비 상태 읽기 전용 점검

기준: 2026-11-20 · GalliPick `com.hebi.gallipick` · 운영 Firebase `gallipick-production` (`asia-northeast3`)

## 목적

기존 앱/서버 코드 테스트만으로는 **실제 신규 계정 결제가 가능하다**고 확인할 수 없습니다. Play Console 상품 활성화와 운영 Firebase Functions의 결제 플래그를 모두 실환경에서 읽고 비교해야 합니다. 이 점검은 실제 구매/환불/배포/상품 변경을 실행하지 않습니다.

## 검증 정책

1. Google Play Developer API `monetization.subscriptions.get`을 사용하여 **GET 요청으로만** 아래 4개 정기결제 상품을 조회합니다.
   - `gallipick_ad_free`: monthly 월 ₩1,000 / annual 연 ₩9,900
   - `gallipick_cloud_10gb`: monthly 월 ₩1,900
   - `gallipick_cloud_50gb`: monthly 월 ₩5,900
   - `gallipick_cloud_100gb`: monthly 월 ₩10,900
2. 각 판매 기본 요금제가 `ACTIVE`, 자동 갱신 `P1M` 또는 `P1Y`, 한국 `KR` 신규 가입 가능, 원화 `KRW` 승인 금액과 일치하는지 확인합니다.
3. Cloud 3개 상품의 annual 기본 요금제는 DRAFT/INACTIVE 또는 모든 지역에서 신규 가입 비허용이어야 합니다. Cloud annual이 어디서든 신규 판매 가능한 ACTIVE 상태라면 **FAIL**입니다. 이미 가입한 Cloud 연간 구독자 권리는 앱/서버에서 별도로 유지합니다.
4. Cloud Functions v2 `getCloudPlan`, `verifyCloudSubscription`은 `projects/gallipick-production/locations/asia-northeast3`의 정확한 대상만 읽습니다.
5. Functions 배포 상태 `ACTIVE`, 전용 서비스 계정, `GALLIPICK_PRODUCTION_AD_FREE_ENABLED=true`, `GALLIPICK_PRODUCTION_BILLING_MODE=live`, 테스트 허용 UID/이메일 목록 비움, `GALLIPICK_PRODUCTION_CLOUD_ENABLED=true`, `GALLIPICK_PRODUCTION_CLOUD_LIVE_ENABLED=true`, 유효한 최대 원본 크기, `GALLIPICK_PRODUCTION_CLOUD_RETENTION_ENABLED=true`, `GALLIPICK_PRODUCTION_CLOUD_UPLOADS_PAUSED=false`를 확인합니다.
6. API에 접근할 수 없거나 권한/설정값을 확인하지 못하면 **UNVERIFIED/NOT_READY**입니다. 누락된 사실을 정상으로 가정하지 않습니다.

## GitHub Actions 실행 준비

워크플로: `.github/workflows/gp-checkout-readonly-audit.yml`

- PR에서는 인증 정보 없이 `scripts/qa/gallipick_checkout_readiness.test.mjs`의 모의 API 응답·오류/경계 테스트만 실행합니다.
- 운영 점검은 main 브랜치에서만 수동 `workflow_dispatch`로 실행할 수 있고, **정확한 main SHA + 읽기 전용 승인 토글**이 모두 필수입니다. 실행 성공은 서버/Play 상품 정보에 대한 조회 성공이며 실제 구독 구매의 성공이 아닙니다.
- 기존 GitHub 변수 `PLAY_WORKLOAD_IDENTITY_PROVIDER` / `PLAY_SERVICE_ACCOUNT`가 설정되고 앱의 정기결제 조회 권한이 있을 때 Play 메타데이터만 읽습니다.
- Cloud Functions 환경을 보려면 별도 최소 권한 계정과 변수 `FIREBASE_READONLY_WORKLOAD_IDENTITY_PROVIDER` / `FIREBASE_READONLY_SERVICE_ACCOUNT`를 준비합니다. 해당 계정에는 `cloudfunctions.functions.get` 등 필요한 **조회 권한만** 부여합니다.
- 위 변수가 없으면 실제 운영 점검을 성공으로 표시하지 않습니다. JSON 서비스 계정 비밀키를 새로 GitHub에 넣지 않습니다.
- `google-github-actions/auth@v3`에서 생성된 단기 토큰은 인증된 요청 헤더에서만 사용합니다. 읽는 메타데이터의 원본 JSON/환경 변수 값, 구매 토큰/이메일/서비스 계정 비밀값을 로그나 artifact에 기록하지 않습니다.
- 출력 artifact에는 상품 ID/지역/가격 조건이 일치하는지, 배포 플래그 **통과/불일치/확인 불가** 여부만 담습니다. 가격 원본·Firebase 설정 원문은 저장하지 않습니다.
- 모든 API 요청은 **HTTP GET**만 수행하며, 정기결제 활성화/수정·Firebase 배포/설정 변경·사용자 결제/환불/백업 삭제를 하지 않습니다.

## 완료 조건·실계정 QA

1. PR 테스트·OPS·Android 필수 CI 성공 및 안전 병합.
2. 최소 권한 WIF 계정 구성과 수동 읽기 전용 점검 실행. 실제 Play와 Firebase 메타데이터 응답이 모두 확인되는지 검증.
3. Play Console에서 필요한 상품의 판매 요금제가 활성인지 검토합니다. Cloud annual은 새 판매 불가를 확인합니다.
4. Google Play 내부 테스트에서 **라이선스 테스터**와 새 Firebase Google 계정으로 ProductDetails 조회→원화 가격/주기 확인→구매 흐름→서버 UID별 entitlement/RTDN/복구 결과를 확인합니다. 라이선스 테스터가 아니면 실제 과금 가능성이 있으므로 보호합니다.
5. 신입 계정 실제 구매·복구, Play 서명/OAuth, 데이터 업로드·광고 면제의 실기기 QA가 끝나기 전에는 **실서비스 결제 완료**로 보고하지 않습니다.
6. 최종 Android 기기별 전체 계측은 모든 PR 개발 완료 후 정확한 최신 main 커밋에서 한 번만 수행합니다. Firebase/Play 배포는 명시적 승인 후 별도 단계입니다.

## 공식 API

- 정기결제 기본 요금제: https://developers.google.com/android-publisher/api-ref/rest/v3/monetization.subscriptions
- 개별 정기결제 GET: https://developers.google.com/android-publisher/api-ref/rest/v3/monetization.subscriptions/get
- Cloud Functions GET: https://cloud.google.com/functions/docs/reference/rest/v2/projects.locations.functions/get

관련 PR: GP-BILLING-ANNUAL-01 (#158), GP-CHECKOUT-NEW-01 (#157).

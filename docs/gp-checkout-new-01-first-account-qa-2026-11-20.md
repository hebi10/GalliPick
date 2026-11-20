# GP-CHECKOUT-NEW-01 — 신규 일반 계정 결제 진입 및 운영 판매 게이트 검증

기준일: 2026-11-20  
상태: 합성 서버 테스트 및 Android UI 회귀 테스트. **운영 Firebase·Play Console 실제 결제 검증은 미실행**.

## 현재 소스에서 확인한 구조

1. 새 사용자가 GalliPick Google 로그인에 성공하면 클라우드 메인 화면으로 진입합니다. 결제 화면은 별도 `요금제·결제` 메뉴를 눌러 표시합니다. 홈 광고 제거의 사용자-시작 진입점은 종전 동작을 유지합니다.
2. Android `CloudBillingSection`은 서버 `getCloudPlan`과 Play Billing `ProductDetails`를 읽습니다. 신규 계정에 `activeProductId`/광고 제거 권한이 없어도 서버에서 `enabled=true`를 주고 Play가 실제 판매 가능한 상품을 반환하면 월/연 요금제를 표시합니다. 선택 전 결제 버튼은 비활성화됩니다.
3. 운영 Firebase `productionBillingSettings`는 `GALLIPICK_PRODUCTION_AD_FREE_ENABLED=true` 및 `GALLIPICK_PRODUCTION_BILLING_MODE=live`일 때 승인 UID 목록이 **비어 있어도** Google 인증 신규 UID의 조회를 허용합니다. `license-test`에서는 테스트 UID 또는 검증 이메일 허용 목록에 없는 신규 계정의 서버 조회를 거부합니다.
4. Cloud 유료 요금제를 실제 노출하려면 `GALLIPICK_PRODUCTION_CLOUD_ENABLED=true`, `GALLIPICK_PRODUCTION_CLOUD_LIVE_ENABLED=true` 및 1~268435456바이트 범위의 `GALLIPICK_PRODUCTION_CLOUD_MAX_ORIGINAL_BYTES`가 필요합니다. 운영 `live`가 아니라면 구독 검증 격리가 달라집니다.
5. Cloud 신규 판매의 서버 `cloudCheckoutAllowed`는 Cloud rollout이 활성이고 `GALLIPICK_PRODUCTION_CLOUD_UPLOADS_PAUSED=false`이며 업로드 버킷의 보안 및 권한 검증도 통과해야 참입니다. UI는 이 상태가 거짓이면 Cloud 상품 구매 버튼을 활성화해서는 안 됩니다. 단독 광고 제거 상품의 실제 Play 판매 가능 여부는 별도로 판단해야 합니다.
6. Android 실제 구매는 앱의 Play Billing `queryProductDetailsAsync`가 구독 상품을 반환할 때만 진행하고, 사용자가 확인한 `ProductDetails` 가격만 표시합니다. 서버는 구매한 계정의 난독화 ID, productId/basePlan, 유효기간, 정기결제 상태, `testPurchase`를 다시 검증합니다. 앱에서 결제 버튼을 누른 사실만으로 혜택을 지급하지 않습니다.
7. 본 작업은 실제 운영 Firebase Functions 환경 변수, IAM, Play Console 상품 ACTIVE 상태, 실계정/Play 테스트 계정 구성, 서명된 Play 앱 구매창을 직접 확인할 수 없으므로 **현재 일반 신규 계정이 실제로 유료 결제할 수 있다고 확정하지 않습니다.**

## 구현한 자동 회귀 테스트

- `firebase/functions/cloud-billing.test.js`
  - 허용 목록에 없는 새 Google UID도 `live` 모드에서 8개 승인 제품/기본 요금제(광고 제거 2, Cloud 6)를 반환. 계정 생성 즉시 유료 권한이나 quota를 지급하지 않음
  - 같은 신규 UID에 Play 검증 정상 구독을 연결한 뒤에만 Cloud 10 GB 할당량 활성화; 다른 UID에는 영향 없음
  - `license-test`에서는 미승인 신규 계정 제한, `live`에서는 라이선스 테스트 구매 거부
  - Cloud live 판매 비활성·업로드 정지·클라우드 프로토콜 불일치 시 Cloud checkout 차단
  - 다른 Google 계정에 바인딩된 구매 토큰으로 유료 혜택 지급 불가
- `app/src/androidTest/.../CloudCheckoutUiTest.kt`: 신규 로그인 사용자가 클라우드 탭을 눌러 결제 메뉴에 직접 진입하는 경로, 요금제 선택 전 비활성 CTA, 상품 선택 뒤 정확한 가격/결제 확인, 자동 구매 없음

## 운영에서 남은 실계정 검증

| 검증 | 필요 근거 | 상태 |
| --- | --- | --- |
| Firebase 실제 배포 환경 | Functions 설정이 `live`이고 결제/Cloud 판매 관련 스위치가 승인된 값인지 | 확인 불가 |
| Google Play 제품 등록 | 판매하는 product ID 및 해당 기본 요금제 ACTIVE·판매 가능 지역·가격 확인 | 확인 불가 |
| Google Play 유료 신규 결제 | Google Play에서 설치한 서명 앱 + 신규 Google 계정 2개 이상 | 미실행 |
| 구매 후 서버 영수증 확인 | Play Developer API 권한, 서버 사용권 부여·RTDN, 타 UID 거부 | 미실행 |
| 구독 만료/환불/업그레이드 | 활성 구독 유지, 보류/취소/환불, 계정 변경 후 중복 구매 방지 | 미실행 |
| Android 화면 계측 | 이번 배치 최종 `main`에서 전체 기기 계측 1회 | 미실행 |

운영 판매 활성화는 사용자의 별도 승인 없이 진행하지 않습니다. 특히 사전 QA에서 실제 신용카드 과금을 발생시키지 않으며 Play 라이선스 테스터/테스트 트랙을 사용하되, 계정이 라이선스 시험 대상이 아니라면 실제 비용이 발생할 수 있으므로 결제 전 반드시 확인합니다.

## 관련 링크

- Firebase 구독 서버: `firebase/functions/cloud-billing.js`, `firebase/functions/index.js`
- Android 앱 구매: `app/src/cloud/java/com/hebi/gallipick/data/cloud/PlayCloudBilling.kt`
- 회귀 테스트 및 정책: `firebase/functions/cloud-billing.test.js`, `app/src/androidTest/java/com/hebi/gallipick/ui/CloudCheckoutUiTest.kt`
- 기존 운영 핸드오프: `docs/production-cloud-handoff-2026-11-18.md`
- 공식 참고: https://developer.android.com/google/play/billing/test · https://developer.android.com/google/play/billing/integrate

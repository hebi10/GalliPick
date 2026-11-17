# CLOUD-04 Google Play 정기 결제 시험

`gallipick-dev`에서 허용된 Google 로그인 계정의 Play **라이선스 테스트 구매**를 검증한다. 개발 서버의 기본값은 구매 기능 비활성화이며, 닫힌 시험에는 기존 서버 저장 한도가 적용된다.

| 환경 변수 | 값 |
| --- | --- |
| `GALLIPICK_BILLING_ENABLED` | 결제 검증이 연결된 개발 시험 환경에서 `true` |
| `GALLIPICK_BILLING_TEST_PRODUCTS` | 허용할 Play 상품 및 기본 요금제 배열. 각 항목은 `productId`, `basePlanId`, `quotaBytes`를 포함하고 상품별 최대 1 GiB |

Firebase callable 응답은 `enabled`, `products`, `activeProductId`, `expiresAtMs`, `uploadAllowed`, `obfuscatedAccountId`, `quotaBytes`, `usedBytes`를 담는다. 클라우드 계정을 확인한 뒤 `getCloudPlan`으로 이 정보를 불러온다. 구매할 가격과 과금 주기는 Play에서 조회한 `ProductDetails`를 표시한다. 구매 화면을 열 때 응답의 `obfuscatedAccountId`를 `setObfuscatedAccountId`에 전달한다. 구매 또는 복구 후 `verifyCloudSubscription`에는 구매 상태가 `PURCHASED`인 `purchaseToken`을 보낸다.

서버는 패키지 `com.hebi.gallipick`을 고정해 `purchases.subscriptionsv2.get`을 조회한다. 라이선스 테스트 구매, Firebase UID 해시, 제품 및 기본 요금제 일치를 확인한 뒤 토큰 해시와 해당 계정의 권한을 같은 트랜잭션에 저장한다. 저장 뒤 Play가 아직 확인하지 않은 구매를 서버에서 승인한다. 승인 실패나 앱 재시작 후 구매 복구로 같은 구매를 다시 검증할 수 있다. 구매 토큰을 문서나 오류 로그에 기록하지 않는다.

구독이 활성 상태이거나 유예·취소 상태에서 유효기간이 남은 경우 서버가 정한 한도 내에서 새 업로드를 허용한다. 공개 예정인 구독은 활성화 전에 Play 실시간 개발자 알림과 갱신·철회 반영을 검증한다. 결제 보류·중지·만료·미확인 상태에서는 새 업로드 권한이 종료된다. 계정의 기존 원본 목록과 복원 권한은 기존 보관 규칙을 따른다.

## 실제 구매 시험 준비

1. 테스트 프로젝트에서 Google Play Developer API를 사용 설정한다. 전용 실행 계정을 GalliPick 앱 단위 권한으로 Play Console에 등록한다. 공식 문서의 재무 데이터 및 주문 조회와 주문 및 구독 관리 권한으로 검증·승인을 수행한다. 결제 함수는 Cloud Run Functions의 런타임 서비스 계정으로 Android Publisher 범위 액세스 토큰을 메타데이터 서버에서 받는다.
2. 별도 승인된 테스트 상품과 기본 요금제를 준비하고 실제 ID를 환경 변수로 지정한다. 테스트 단말에서 Google Play 라이선스 테스터 계정을 사용한다. Play 계정과 앱 Firebase 계정의 연결은 구매 시 제출한 난독화된 식별자로 확인된다.
3. 개발 프로젝트의 전용 토픽 `gallipick-play-rtdn-test`를 Play 실시간 개발자 알림에 연결한다. `refreshCloudSubscription`은 Pub/Sub 이벤트를 받으면 Google Play API에서 상태를 다시 읽는다. `unavailable` 또는 `aborted`는 다시 시도한다. 인증 실패·상품 불일치 이벤트는 거부하며, 원문 메시지나 토큰을 로그에 남기지 않는다.
4. 승인·중복 호출·타 계정 토큰·유예·취소·보류·만료·복구·구독 교체 상태를 실기기에서 확인한다. 시험을 닫을 때 `GALLIPICK_BILLING_ENABLED`를 끄고 개발 상품 설정을 검토한다.

공식 참고 [결제 통합](https://developer.android.com/google/play/billing/integrate) · [라이선스 테스터](https://developer.android.com/google/play/billing/test) · [Play Developer API 권한](https://developers.google.com/android-publisher/getting_started) · [구독 상태](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2) · [실시간 개발자 알림](https://developer.android.com/google/play/billing/rtdn-reference)

# GP-BILLING-ANNUAL-01 — Cloud 신규 연간 판매 중단 및 기존 계약 보존

기준일: 2026-11-20
근거 기획: [GalliPick 정리영상광고·클라우드 요금제 통합기획](https://docs.google.com/document/d/1nm-znFD9MXNbmYSKqruEA2VkT-PMZusoL1VoevrD-8w/edit) (2026-11-19 Cloud 신규 월간만 확정)

## 적용 정책

- 신규 Cloud 10GB/50GB/100GB 구독은 월간 `monthly`만 제공합니다. 신규 고객에게 Cloud `annual` 상품과 과거 연간 할인 금액을 표시하거나 제안하지 않습니다.
- 광고 제거 단독 `gallipick_ad_free`는 `monthly` 및 `annual`을 유지합니다.
- 과거에 Google Play에서 구매한 Cloud 연간 구독자의 **유효한 기간·광고 제거·용량·갱신·복원**은 신규 판매 중단과 독립적으로 보존합니다.
- 운영 기준 2026-11-20 00:00 KST부터 새로운 Cloud 연간 판매는 서버에서 거부하도록 제한합니다. 그 이전의 구매 시작 시각이 Google Play API에서 확인되고 UID가 일치하는 경우는 이전 구매로 복구할 수 있습니다.
- 기존 서버에 이미 동일 UID로 연결된 구매 토큰 해시는 원본 구매 시작 시각이 응답에서 빠져도 재검증할 수 있습니다. 새로운 미등록 연간 영수증에는 구매 시작 시각이 없거나 기준일 이후이면 권한을 지급하지 않습니다.
- 라이선스 시험도 신규 연간 Cloud 판매 목록은 숨깁니다. 기존 테스트/운영 연간 구매 복구 경로는 별도 검증을 통해 유지합니다.

## 구현 방식

1. `productionMonetizationCatalog(true)`는 과거 유효 영수증 검증을 위한 **전체 8개 조합**(광고 제거 월·연 2개 + Cloud 월·연 6개)을 유지합니다.
2. 새 `productionNewSaleCatalog(true)`는 **판매 대상 5개 조합**(광고 제거 월·연 2개 + Cloud 월간 3개)만 반환합니다.
3. 서버 `productionBillingSettings`는 새 판매 상품을 앱에 내려주고, `verificationProducts`는 과거 연간 영수증을 승인된 목록과 비교하는 데만 사용합니다.
4. 서버 `persist` 트랜잭션에서 Play가 제공하는 `startTime`과 과거 구매 토큰 소유권 기록을 검사하여 신규 연간 영수증 권한 부여를 차단합니다. 거래 내에서 기존 구매 연결 기록을 확인하므로 중복/경합에도 같은 정책을 적용합니다.
5. 이미 서버에서 확인된 유료 연간 Cloud 구독은 앱에서 현재 이용 중인 상품을 보여줄 수 있도록 `products`에 해당 조합만 예외 포함합니다. 기존 연간 구독의 영수증 복구가 가능하도록 판매 목록과 별개인 `legacyRecoveryProducts` 필드를 제공합니다.
6. Android `PlayCloudBilling`은 `legacyRecoveryProducts`의 정상 Cloud 연간 용량·상품만 별도 복구용으로 사용하고 `loadOffers` 구매 대상에는 포함하지 않습니다.
7. `CloudBillingSection`은 서버가 과거 연간 Cloud 상품을 반환하더라도 새로운 구매/교체용 Cloud 연간 버튼을 숨깁니다. 미판매 요금제 안내에서 연간 가격을 제거합니다. 광고 제거 연간 요금제와 그 Google Play 실제 금액은 유지합니다.
8. Android 화면 캡처용 모의 요금제도 Cloud 월간 3종과 광고 제거 월·연 2종으로 정리했습니다.

## 검증 기준

- Node: 신규 `live` Google 계정의 Cloud 판매 3종 월간만 표시, 광고 제거 연간 유지, Cloud 연간 신규 영수증 거부, 기준일 이전 Play 가입 이력 승인, 동일 UID의 기존 연간 구매 재검증·용량·광고 제거 유지, 타 계정 영수증 거부.
- Android JVM/Compose: 신입 가입자에게 Cloud 연간 금액·CTA 없음, Cloud 월간 구매 옵션과 광고 제거 연간 구매 가능, 기존 Cloud 연간 구매 복구용 상품 목록과 실제 판매 목록 분리.
- PR 최신 HEAD의 Cloud backend·Android verification·OPS-07/09 검사 성공 후 병합. 최종 전체 기기 계측은 개발 배치 완료 뒤 최신 main SHA에서 1회 시행.

## 운영 조치 (미수행)

- Play Console에서 Cloud 3종의 `annual` 기본 요금제가 신규 구독 가능 상태인 `ACTIVE`라면 기존 가입자 보호를 검토한 후 신규 판매 불가 `INACTIVE`로 관리해야 합니다. 초기 `DRAFT`인 경우 활성화하지 않습니다.
- Cloud `monthly` 및 광고 제거 `monthly/annual`은 기획 가격, 판매 지역, 기존 약정/과금과 일치하는지 읽기 전용으로 재검증합니다.
- Firebase Functions의 `live` 판매 스위치와 실제 Play 서명 앱의 판매 상품 확인은 별개입니다. 운영 런타임 상태를 직접 확인할 수 없으므로 실결제 허용을 완료로 보고하지 않습니다.
- 이번 코드는 **운영 Firebase/Play Console 상품 수정·배포·구매·환불·기존 구독 자동 변경을 실행하지 않습니다**.

관련 공식 문서: https://developers.google.com/android-publisher/api-ref/rest/v3/monetization.subscriptions · https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2

# GP-ADS-01 — GalliPick 광고·광고 제거 구독 운영 준비

기준일: 2026-11-16
대상: `hebi10/GalliPick` / 패키지 `com.hebi.gallipick`

## 결론 및 작업 범위

- QuickCut과 같은 방식으로 **GalliPick도 광고 있음/광고 ID 사용을 신고할 준비**를 합니다. QuickCut에서 저장했다는 콘솔 결과는 **GalliPick 앱에 자동 적용되지 않습니다**.
- GalliPick은 원본 정리·확인·삭제 승인·사진 비교가 핵심이므로 초기 광고는 **홈 화면의 하단 단일 배너**만 사용합니다. 파일 목록, 정리, 클라우드, 이미지·동영상 뷰어, 승인 모달, 설정에는 광고를 배치하지 않습니다.
- 유효한 클라우드 구독에는 홈 배너 광고 제거가 포함됩니다. 클라우드 용량 없이 광고 제거만 구매하는 방안은 별도 상품 설계로 남아 있습니다.
- 현재 PR은 GMA Next-Gen SDK 1.6.0, UMP 4.0.0, 홈 배너, 광고 동의·설정, 구독 권한 적용을 위한 정책 계층을 포함합니다. **광고 제거 구매·서버 영수증 검증·Play Console 설정 저장은 이번 PR에서 완료했다고 표시하지 않습니다.**

## 1. 현재 코드의 변경

- Google Mobile Ads Next-Gen SDK `com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.6.0` 통합
- UMP `com.google.android.ump:user-messaging-platform:4.0.0` 통합: 매 앱 실행 시 동의 상태 조회, 필요한 동의 양식, 사용자가 변경해야 할 때 설정 진입점 제공
- 컴파일된 앱의 광고 표시 위치를 홈 한 곳으로 제한. 광고 미로드 시 빈 공간을 점유하지 않음
- Google 제공 공식 **테스트 AdMob 앱 ID와 테스트 배너 단위 ID**만 디버그 테스트에 활용
- 실제 AdMob 앱 ID/배너 ID는 Gradle 빌드 입력으로만 받고, 기본 배포 구성에서는 광고 요청을 비활성화
- Play Billing 9.1.0을 모든 Android 빌드에 포함해 광고 제거 구독용 BILLING 권한을 준비. 기존 `cloud` 구독 모델을 변경하지 않음
- 홈 배너 광고 제거는 현재 계정의 서버 확인 상품 ID와 만료 시각으로 결정합니다. 조회 전·실패 시에는 광고를 요청하지 않습니다. 앱으로 돌아오거나 구매·복원 결과를 받으면 다시 확인하고, 포그라운드에서 최대 5분마다 또는 유효 기간 종료 시 다시 확인합니다.

## 2. 빌드 설정

디버그 빌드에서 테스트 광고 활성화:

```sh
gradle -PgallipickTestSigning=true -PgallipickAdsTest=true :app:assembleDebug
```

일반 디버그와 배포 준비 빌드는 **기본적으로 광고 기능 비활성화**됩니다.

개발 Firebase를 사용하는 Play 내부 테스트용 클라우드 파일럿 빌드에는 홈에 `광고 표시 테스트` 배너가 표시됩니다. 이 배너는 AdMob 요청 없이 앱이 표시하며, 활성 클라우드 구독을 서버에서 확인하면 숨깁니다. 실제 광고를 활성화한 빌드에서는 이 배너를 표시하지 않습니다.

개발자가 독립된 GalliPick AdMob 앱과 배너 광고 단위를 생성하고, 광고·결제 검증 및 Play 신고까지 완료한 경우에만 운영 빌드에 다음 속성을 전달합니다.

```sh
gradle -PgallipickAdsEnabled=true \
  -PgallipickAdMobAppId=<GALLIPICK_ADMOB_APP_ID> \
  -PgallipickAdMobBannerUnitId=<GALLIPICK_BANNER_UNIT_ID> \
  :app:bundleRelease
```

위는 **설정 형식 예시**입니다. 실제 ID는 AdMob 계정에서 발급받아 사용하며 QuickCut의 ID를 재사용하지 않습니다. 현재 저장소에 실제 광고 ID를 저장하지 않습니다.

## 3. Play Console 작업 — GalliPick 별도 앱

GalliPick의 과거 Play 신고 `광고 없음` / `광고 ID 미사용`은 광고가 포함된 새 빌드와 일치하지 않습니다. 콘솔의 **앱 콘텐츠**에서 다음을 실제 변경·저장해야 합니다.

1. 광고: **‘광고 있음(예)’**으로 수정하고 저장합니다.
2. 광고 ID: **사용 여부 ‘예’** 및 광고 제공 목적에 맞는 문항을 재검토합니다.
3. 데이터 보안: GMA SDK/Google이 수집·처리할 수 있는 광고 식별자, 기기·앱 정보, 앱 상호작용 데이터 및 앱 자체 클라우드 처리 범위를 최신 SDK 데이터 공개 안내와 실제 운용 기준으로 재작성합니다. 무조건 ‘수집 없음’으로 유지하면 안 됩니다.
4. 개인정보처리방침: `docs/privacy.html`의 새 공개 내용을 확인하여 Play에 등록된 URL과 일치시킵니다.
5. 대상 연령: 과거 13세 이상으로 신고된 연령대와 광고 서비스 설정의 아동/청소년·맞춤 광고 제한 정책을 대조합니다.
6. 게시 개요: 설정이 저장·검토 제출됐는지를 구분하여 확인합니다. 저장만으로 승인·게시 완료가 아닙니다.

**이 프로젝트의 Play Console 값은 아직 직접 수정·저장하지 않았습니다.**

## 4. AdMob 작업 — GalliPick 고유 등록

1. Google AdMob에서 Android 앱 패키지 `com.hebi.gallipick`을 별도 등록합니다.
2. 발급된 AdMob App ID와 **홈 배너 전용 광고 단위 ID**를 확인합니다.
3. 개인 정보 보호 및 메시지에서 EU/EEA·영국·스위스 규정에 필요한 UMP 메시지를 구성·게시합니다.
4. 기존 테스트 광고 ID만 사용해 실제 기기에서 광고 로드·닫기·화면 전환·개인정보 선택을 검증합니다.
5. 실제 AdMob 앱 심사·정책·앱-광고파일(`app-ads.txt`) 요구사항을 확인합니다.
6. 한국·해외 데이터 안전 신고와 개인정보처리방침을 함께 갱신합니다.

## 5. 별도 광고 제거 상품 — 운영 설계 초안

QuickCut에서 언급된 가격을 GalliPick **광고 제거 전용 상품의 제안값**으로 사용합니다. 실제 설정은 Google Play에서 가격·세금·계정 정책을 확인한 후 확정합니다.

| 항목 | GalliPick 상품 설계 |
| --- | --- |
| 구독 상품 ID | `gallipick_ad_free` |
| 월간 기본 요금제 ID | `monthly` |
| 월간 가격 제안 | KRW 1,000 / 1개월 |
| 연간 기본 요금제 ID | `annual` |
| 연간 가격 제안 | KRW 9,900 / 1년 |
| 효과 | GalliPick 홈 광고 제거 |
| 포함하지 않음 | 클라우드 용량 추가·무제한 백업·원본 저장 정책 변경 |

- Play Console > 수익 창출 > 제품 > 정기 결제에서 단일 상품과 두 개의 자동 갱신 기본 요금제를 구성합니다.
- Play Billing 권한을 포함한 **서명된 AAB를 테스트 트랙에 업로드**해야 상품 등록이 가능할 수 있습니다.
- 상품을 판매하기 전 Android 구매·복구·보류·취소·만료를 구현하고 **서버 Google Play Developer API 검증, 구매 토큰 고유 처리, 계정 연동 정책, RTDN 갱신**을 추가해야 합니다.
- 서버에서 활성화가 확인된 계정에만 `VERIFIED_AD_FREE` 권한을 부여하고 배너 요청을 중지합니다. 결제 화면을 닫았다는 사실만으로 광고 제거하지 않습니다.
- 광고 제거 전용 상품을 구현할 때는 클라우드 구독과 별도로 서버에서 검증한 권한을 광고 판정에 함께 반영합니다.

현재 **광고 제거 상품은 Play Console에 등록되지 않았으며, 결제 기능도 완성되지 않았습니다.**

## 6. 테스트·배포 게이트

- [ ] 광고 비활성화 일반 디버그/배포 빌드에서 광고 네트워크 요청 0건
- [ ] 테스트 광고 전용 빌드에서 Google 공식 테스트 배너 표시
- [ ] 동의가 허용되기 전 광고/SDK 초기화 금지
- [ ] 동의 재선택·철회 시 새 광고 요청 금지
- [ ] 홈에서만 광고 노출, 파일·정리·클라우드·모달에서 광고 미노출
- [ ] 화면 회전·다크모드·태블릿·글씨 160%에서 앱 조작 영역과 광고가 겹치지 않음
- [ ] 클라우드 구독의 유효·만료·구매 복원·조회 실패·계정 전환에서 홈 배너 표시 검증
- [ ] 별도 광고 제거 상품의 구매·복원 검증(상품 구축 후)
- [ ] App 콘텐츠 광고·AD_ID·데이터 보안·개인정보처리방침 저장·심사 상태 검증
- [ ] 정식 AdMob ID를 **GalliPick 본인 것**으로 주입한 후 내부 테스트 업로드 및 실제 광고가 아닌 테스트 기기로 확인

## 공식 문서

- 광고 신고: https://support.google.com/googleplay/android-developer/answer/9859455
- Next-Gen GMA SDK: https://developers.google.com/admob/android/next-gen/quick-start
- 배너 광고 및 테스트 ID: https://developers.google.com/admob/android/next-gen/banner
- UMP 동의 관리: https://developers.google.com/admob/android/privacy
- SDK 최신 버전: https://developers.google.com/admob/android/next-gen/rel-notes
- Play Billing 상품 등록: https://developers.google.com/chromeos/app-development/publish/play-console-setup-for-billing

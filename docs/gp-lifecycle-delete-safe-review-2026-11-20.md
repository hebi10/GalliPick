# GP-LIFECYCLE-DELETE — 만료 후 14일 삭제 대상 확인 단계

기준일: 2026-11-20
상태: 삭제 후보 검토 스케줄러 구현. **자동 삭제 실행은 보류**.

## 배경

- 유료 Cloud 구독은 Google Play가 확인한 유료 기간 종료 후 7일 동안 기존 파일을 읽고 복원할 수 있고, 7일부터 14일까지는 접근이 차단됩니다.
- 14일 경과 시점에 실제 Storage 삭제가 가능하려면 Google Play Developer API에서 **다시 구독 상태를 조회**해 갱신/복원/유예/취소를 확인해야 합니다.
- 현재 운영 결제 문서의 `cloudLifecycle.tokenHash`는 SHA-256 해시입니다. 이 해시만으로 `purchases.subscriptionsv2.get`을 호출할 수 없습니다. 원본 구매 토큰을 암호화 저장하거나 다른 검증 경로를 확보하기 전에는 14일 경과만으로 사용자 사진·영상 자동 삭제를 실행하지 않습니다.

## 구현

- `firebase/functions/cloud-expiry-purge-review.js` — 두 개의 서로 분리된 운영 결제 스코프를 조회해 만료된 Cloud 상품의 검증 기록을 판정합니다.
- `reviewCloudExpiryPurges` — 12시간마다 실행 가능한 서버 스케줄러입니다. 기본 비활성이고 `GALLIPICK_PRODUCTION_CLOUD_RETENTION_ENABLED=true` 및 `GALLIPICK_CLOUD_PURGE_REVIEW_ENABLED=true`가 모두 설정된 경우에만 실행됩니다.
- 후보가 유효할 때 `gallipickCloudPurgeReviews`에 `NEEDS_PLAY_RECHECK` 상태, UID 해시, 서버 검증 만료일·검증시각·현재 결제 문서 revision만 기록합니다.
- 암호화되지 않은 구매 토큰, Google 사용자 이메일, 실제 파일 이름, 개인 미디어는 후보 기록에 저장하지 않습니다.
- 리뷰 스케줄러는 원본 삭제 작업을 예약하거나 `processAccountExits`에 어떤 요청도 만들지 않습니다.
- 동일 Firebase UID가 다른 결제 스코프에서 활성 이용권을 보유했거나 결제 기록이 불완전하면 삭제 대상으로 추천하지 않습니다.
- `ACCOUNT` 탈퇴·`CLOUD` 삭제 요청 중에는 만료 기반 후보를 만들지 않습니다.

## 검증

- 14일 경계 1ms 이전/정확한 시점, 상품·구매 토큰 해시·계정 revision 불일치, 결제 갱신, 구독 상태 미확인, 이미 진행 중인 삭제 작업, 별도 라이선스 테스트 결제 기록과 같은 UID 사용자 안전성에 대해 Node 테스트를 추가합니다.
- `Cloud backend verification`에서 순수 판정과 review-only 기능을 테스트합니다.
- 인위적인 후보 계정/원본은 Firebase Emulator에서 검증하되, **실제 고객 파일 삭제는 실행하지 않습니다**.

## 자동 삭제를 완성하기 위한 선행 작업

1. 원본 토큰을 서버측에 안전하게 암호화 보관하거나 Play가 인증한 상태를 독립적으로 재조회할 수 있는 경로 마련. Firebase Secret Manager 또는 Cloud KMS를 활용하며 비밀키는 GitHub에 저장하지 않습니다.
2. 실제 `subscriptionsv2.get` 호출로 소유자·상품·기존 결제 이력·갱신·계정 변경·환불 상태 재검증.
3. 삭제 시작 직전 동일 UID의 라이선스/운영 양쪽 결제 기록과 모든 유료/대체 상품을 다시 검사하고, 경합을 막는 서버 잠금을 확보.
4. 신규 구매/갱신 중이거나 복원 가능기간이 남아 있다면 삭제 예약을 철회. 빈 Storage·Firestore 최종 확인, 실패 재시도·관리자 알림.
5. 기존 사용자 만료일 기록을 이전·검증하고, 관리자에서 검토대상·오류를 확인한 후 명시적 배포 승인으로 서버 삭제 스케줄러 활성화.

## 관련 작업

- PR #148 / #149: 본인 요청 데이터 삭제 API와 Firebase Emulator 검증.
- PR #150: Android 앱의 탈퇴·클라우드 삭제 API 연결(별도 진행).
- GP-ADMIN-01: 관리자 Hosting 기본 구성, 검토 후보 목록 조회.
- Google Play subscription API: https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2/get

이 문서와 코드에서 사용하는 `NEEDS_PLAY_RECHECK`는 삭제 가능 승인이 아니며, 기한이 지났다고 삭제 완료가 되지는 않습니다.

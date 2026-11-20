# GP-LIFECYCLE-PLAY-01 — Google Play 구매 토큰 Cloud KMS 암호화 보관

기준일: 2026-11-20
상태: 구현·검증 PR. **KMS 키 미생성, 운영 기능 비활성, 실제 구매·삭제·Firebase 배포 미실행.**

## 배경 및 범위

현재 GalliPick 운영 Firestore는 Google Play 구매 토큰 SHA-256 해시로 소유자별 구독과 만료 기록을 관리합니다. 해시만으로는 Google Play Developer API `purchases.subscriptionsv2.get`의 최신 상태를 재조회할 수 없기 때문에, 구독 만료 14일 시점의 사용자 클라우드 원본을 삭제할 수 없습니다. 기존 GP-LIFECYCLE-DELETE는 삭제 후보 `NEEDS_PLAY_RECHECK`만 남기며 삭제하지 않습니다.

본 작업은 **향후 서버에서만 사용할 수 있는 구매 토큰의 KMS 암호화 저장·복호화 기반**입니다. 아직 자동 삭제 워커, 실제 Play 최신 구독 재조회 또는 결제 정책 변경을 실행하지 않습니다.

## 암호화 구현

- `firebase/functions/play-token-escrow.js`: Cloud KMS의 대칭 `cryptoKeys.encrypt`/`cryptoKeys.decrypt` REST API 사용. 원본 구매 토큰을 응답/로그/Firestore에 평문으로 기록하지 않습니다.
- KMS CryptoKey 리소스는 `projects/gallipick-production/locations/asia-northeast3/keyRings/<승인 키링>/cryptoKeys/<승인 키>` 형식의 운영 프로젝트/리전으로 한정합니다. 서비스 계정은 KMS 키에만 `cloudkms.cryptoKeyVersions.useToEncrypt`/`cloudkms.cryptoKeyVersions.useToDecrypt` 권한이 필요합니다.
- KMS의 `additionalAuthenticatedData`는 프로젝트·`live`/`license-test` 모드·Firebase UID SHA-256 해시·구매 토큰 SHA-256을 결합합니다. 다른 사용자/환경/토큰의 ciphertext를 복사해도 복호화하지 못하게 서버에서 바인딩을 확인합니다.
- 본문 ciphertext는 KMS API 반환값이며, KMS 제공 키 자체는 Google Cloud KMS에서 보관합니다. 노출 가능한 서버 예외도 사용자에게 상세 공급자 URL/원본 응답을 전달하지 않습니다.
- Cloud KMS cryptoKeyVersion의 일반 키 버전 교체는 동일 CryptoKey 이름 아래에서 관리할 수 있습니다. **키 리소스 자체 변경과 키 폐기는 암호문 복구·재암호화 검증 후** 진행해야 합니다.
- 현재 Firestore Rules는 클라이언트 전체 읽기/쓰기를 거부합니다. 구매 토큰 암호문 문서 역시 Admin SDK 서버 액세스만 허용합니다.

## Firestore 기록·결제 연동

- `live` : `gallipickProductionBillingTokenEscrows/<sha256(purchaseToken)>`
- `license-test`: `gallipickProductionLicenseBillingTokenEscrows/<sha256(purchaseToken)>`
- 계정 식별자, 환경, 원본 토큰 SHA-256, 키 리소스, KMS ciphertext, 마지막 Play 서버 검증 시각, 포맷 버전만 저장합니다.
- 기존 `gallipickProductionBillingTokens` 및 계정별 entitlement는 **변경하지 않습니다**. 결제 검증 서버는 Google Play에서 실제 Cloud 상품/권리를 확인한 뒤 KMS 암호화를 요청합니다.
- 기존 Firestore 결제·토큰 소유권의 revision/owner 일치 검사를 통과한 **동일 트랜잭션 내**에서만 escrow를 저장합니다. 다른 UID와 연결된 토큰에는 escrow를 저장하지 않습니다.
- `GALLIPICK_PRODUCTION_PLAY_TOKEN_ESCROW_ENABLED=true`로 명시 활성화된 경우에만 신규 검증 Cloud 영수증을 암호화합니다. **기본 OFF**. 키 리소스 설정 `GALLIPICK_PRODUCTION_PLAY_TOKEN_KMS_KEY`가 유효하지 않으면 활성화는 실패해야 합니다.
- KMS API 장애 시 해당 확인 건의 escrow를 누락 처리하지만, 이미 Google Play가 검증한 결제 권리와 클라우드 업로드 자격은 정상적으로 기록합니다. **암호문이 없는 계정은 향후 자동 삭제 판정에서 반드시 차단**되어야 합니다. 암호화 실패를 구매 실패나 결제 취소로 오인하지 않습니다.
- 만료 이후에 새로 유입되거나 KMS 활성화 이전에 구독한 고객은 즉시 암호화 저장할 구매 토큰이 없을 수 있습니다. 과거 고객은 **명시적으로 구매 복구·Play 서버 재검증**을 통해 암호문이 생성될 수 있으나, 과거 기록만으로 원문 토큰을 재구성하지 않습니다.

## 회원 탈퇴·Cloud 데이터만 삭제

- 전체 회원 탈퇴의 서버 삭제 단계는 두 모드의 `*BillingTokenEscrows`와 사용자 소유 원본/결제 기록을 함께 제거하고 Firebase Auth 계정 삭제를 마지막에 실행합니다.
- Cloud 데이터만 삭제할 때에는 기존 유료 구독/자동 갱신 의무와 분리하여 암호문 보관 기록을 지우지 않습니다. 따라서 회원 계정/Google Play 구독 상태를 자동 취소·환불하거나 다른 사용자의 토큰을 지우지 않습니다.

## 운영 활성화 전 선행조건

1. 운영 프로젝트 KMS API 사용 설정 및 대칭 ENCRYPT_DECRYPT 키 생성; 적절한 키 리전, 버전 회전, 감사 로그·키 폐기 방지 정책 확정.
2. 결제 전용 Cloud Functions 서비스 계정에 **해당 키에 한정된** KMS encrypt/decrypt 권한을 부여하고, 앱·관리자 웹 클라이언트에는 부여하지 않습니다.
3. 영수증 확인/RTDN/계정 탈퇴 경로를 Firebase Emulator·합성 KMS로 검증하고 실제 Cloud KMS 키에 대한 읽기/암호화/복호화 테스트를 승인된 합성 토큰으로만 수행합니다.
4. 운영 `GALLIPICK_PRODUCTION_PLAY_TOKEN_ESCROW_ENABLED`와 키 경로는 보안 검증 후 승인된 배포 단계에서만 설정합니다. 이미 운영 중인 결제 사용권에 영향을 주지 않는지 테스트합니다.
5. 후속 GP-LIFECYCLE-PLAY-02에서 **KMS 복호화→Google Play subscriptionsv2.get 최신 상태 재조회→다른 활성 이용권·관리자 지급 이용권 확인→후보 최신성·동시성 재검증**을 구현합니다. 검증 실패, 미암호화 기록, Play API 일시 장애 시 자동 삭제는 금지합니다.
6. 원본 자동 삭제 워커 연결은 별도 검증/승인을 요구하며, 7일 복원·14일 삭제 기산점과 사용자 고지/정산/환불 상태까지 확인되기 전에는 실행하지 않습니다.

## QA

- `play-token-escrow.test.js` : 운영 프로젝트/리전, 승인 모드와 key path, AAD 소유권·토큰 바인딩, 데이터 변조·권한 오류·키 교차 검증, 예외 내 민감정보 비노출.
- `cloud-billing.test.js`: 기존 결제 권리 보존, 유효 Cloud token의 opt-in KMS ciphertext 기록, 동일 UID/다른 환경 격리, KMS 장애 fail-closed 및 기존 dev/기본 OFF 보존.
- `account-exit.test.js` 및 `account-exit.emulator.test.js`: CLOUD-only 삭제에서는 유료 결제/escrow 유지; 전체 ACCOUNT 탈퇴에서는 두 운영 모드 escrow 모두 삭제, 다른 회원 격리.
- PR Cloud backend verification 및 Firebase Emulator deletion QA, OPS-07/09, Android verification의 최신 HEAD 성공 확인 후만 병합.
- 운영 Firebase, Google Play Console, 실제 유료 계정·클라우드 사용자 데이터는 이번 코드 개발 중 변경하지 않습니다.

참고: https://docs.cloud.google.com/kms/docs/reference/rest/v1/projects.locations.keyRings.cryptoKeys/encrypt · https://docs.cloud.google.com/kms/docs/reference/rest/v1/projects.locations.keyRings.cryptoKeys/decrypt

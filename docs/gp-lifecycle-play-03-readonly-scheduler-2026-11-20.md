# GP-LIFECYCLE-PLAY-03 — Cloud 만료 Play 재검증 예약 실행 (읽기 전용)

기준일: 2026-11-20
상태: 기본 OFF의 Firebase Scheduler 코드 및 안전 검사. 실제 운영 Scheduler/Cloud Functions 미배포.

## 역할

서버에 저장된 KMS 암호문이 있는 만료 Cloud 구매 기록만 Google Play에서 최신 구독 상태를 다시 조회하고 **건수만 집계**하는 서버 작업입니다. 사용자 UID, Play 토큰, 이메일, 암호문, 원본 파일명/URL은 Cloud Scheduler 로그와 관리자 브라우저에 출력하지 않습니다. 조회 결과가 만료로 확인되어도 `deletionAllowed=false` 및 `deletionExecuted=false` 정책을 유지하며, Storage/Firestore/Auth 고객 데이터를 삭제·수정하지 않습니다.

## 구현

1. `firebase/functions/cloud-play-recheck-runner.js`에서 운영 프로젝트 `gallipick-production` 및 아래 **모든 활성 스위치**를 검사합니다.
   - `GALLIPICK_PRODUCTION_AD_FREE_ENABLED=true`
   - `GALLIPICK_PRODUCTION_CLOUD_RETENTION_ENABLED=true`
   - `GALLIPICK_CLOUD_PURGE_REVIEW_ENABLED=true`
   - `GALLIPICK_PRODUCTION_PLAY_TOKEN_ESCROW_ENABLED=true`
   - `GALLIPICK_PRODUCTION_PLAY_RECHECK_ENABLED=true`
   - `GALLIPICK_PRODUCTION_PLAY_TOKEN_KMS_KEY`: 운영 프로젝트·서울 리전 키 리소스
2. `firebase/functions/index.js`에 `recheckCloudExpiryWithPlay` V2 `onSchedule` 추가. 기본 12시간마다, 최대 인스턴스 1, 동시 실행 1, 타임아웃 120초, `live`/라이선스 시험 각 최대 3개 후보만 재조회합니다.
3. 출처별 암호문을 승인된 `createKmsPurchaseTokenEscrow` 모듈로 복호화하고, `createPlaySubscriptions().get`으로 Play Developer API GET을 수행합니다. `cloud-play-expiry-recheck.js` 모듈이 UID 소유권, SKU/base plan, 서버 데이터 revision, 최신 Play 만료일·14일 보존 기산점, 다른 활성 이용권/관리자 지급 혜택을 재확인합니다.
4. `PLAY_EXPIRED_CONFIRMED`도 **삭제 허가가 아닙니다**. 미암호화 기록, KMS/Play 조회 장애, 불명확한 상태, 재가입·갱신 대상은 보수적으로 `UNVERIFIED` 또는 보류 분류합니다.
5. 결과는 status별 건수와 삭제 0건만 안전하게 집계합니다. 실제 고객 데이터의 보존 기간을 갱신하거나 삭제 예약을 생성하지 않습니다.

## 운영 활성화 전 조건

- KMS AES-GCM CryptoKey와 사용 권한에 대한 운영 승인 및 최소권한 서비스 계정 IAM 검증.
- Play Publisher API가 해당 패키지 `com.hebi.gallipick`의 subscriptionsv2 조회를 허용하는지 안전한 테스트 토큰으로 검증.
- Firebase `gallipick-production`의 만료 기록/다른 이용권 상태/기존 구매 토큰 암호문이 해당 데이터 주체와 정확하게 연결됐는지 검증.
- 기존 고객의 구매 토큰 SHA만 남아 KMS 암호문이 없는 경우는 자동 삭제 대상에서 제외. Cloud 사용자에게 구매 복구를 강요하거나 서비스 권한을 임의로 회수하지 않음.
- 서명 AAB/Google Play 사용자 결제/환불·Firebase 배포·운영 스위치 활성화는 이번 코드 개발에 포함하지 않음.
- 스케줄러 배포 자체가 Cloud Scheduler 비용/쿼터를 유발할 수 있으므로 운영 배포는 전체 QA 및 사용자의 별도 승인을 요구함.

## 검증

- `cloud-play-recheck-runner.test.js`: 운영 프로젝트/활성 스위치/KMS 키 경로/검토 건수/실행 주기 fail-closed.
- PR Cloud backend 및 기본 Android·OPS 필수 검사.
- 스케줄러 운영 검증은 별도 승인 후 **합성 고객 계정만** 대상으로 실행. 최초 활성화는 실제 삭제 기능과 연결되지 않은 읽기 전용 상태로만 수행.

후속: GP-LIFECYCLE-DELETE 실제 14일 객체 삭제는 Play 최신 검증 후에도 별도 트랜잭션·임대 잠금·사용자 고지·복구 검증 및 명시적 배포/삭제 승인 전 금지.

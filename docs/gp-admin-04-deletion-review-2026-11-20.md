# GP-ADMIN-04 — 삭제 요청·오류 검토·일괄 사전 검토

기준: 2026-11-20
상태: GitHub PR 구현 및 CI 검증 단계. 운영 Firebase·Hosting·Google Play **미배포**.

## 관리 화면

- 기존 관리 웹에 '삭제 요청 및 운영 검토' 영역을 추가합니다.
- 계정 전체·클라우드 데이터 삭제 요청은 `gallipickAccountExitRequests`에서 읽습니다. 'Google Play 만료 재검증 대기'는 `gallipickCloudPurgeReviews`에서 별도로 읽습니다.
- 요청 접수 시각, 처리 단계, 실패·재시도 상태, 기존 삭제 요청 ID, 처리 목표 시각을 표시합니다. Firebase Auth의 원본 UID, 이메일, 구독 구매 토큰/해시, 사진·동영상 파일명/URL은 클라이언트로 반환하지 않습니다.
- 페이지당 15건 제한. 서버가 확인한 소유자 해시와 조회 순서를 사용하며 클라이언트 지정 컬렉션이나 임의 Firestore 경로를 받지 않습니다.
- 최대 10건의 '선택한 요청 사전 검토'는 결과만 돌려줍니다. `canExecute:false`, `serverDeletesPerformed:0`인 비파괴적 dry-run이며 일괄 삭제 실행 버튼·백엔드 엔드포인트는 **구현하지 않습니다**.
- 만료 검토는 항상 `NEEDS_PLAY_RECHECK`입니다. 마지막 검증 만료일만으로 Google Play 최신 결제 상태 확인 없이 사진·영상을 삭제할 수 없습니다.

## 개별 오류 재시도

- 대상은 **사용자가 이전에 직접 본인 확인 후 요청한 삭제 기록** 중 `MANUAL_REVIEW`에 있는 항목만 허용합니다.
- Google 인증·verified email·custom claim `gallipickAdmin=true`·등록된 UID 해시 허용 목록과 최근 10분 Google 재인증을 모두 요구합니다.
- 관리자가 현재 기록의 범위를 다른 계정이나 CLOUD → ACCOUNT로 확대할 수 없습니다. 원본 `requestId`, scope, stage, 원본 Firebase UID의 SHA-256 매칭을 서버에서 다시 확인합니다.
- 요청은 기존 삭제 작업의 `status`를 `RETRY`로 전환해 이미 승인된 동일 단계부터 스케줄러가 이어서 처리할 수 있도록 합니다. 사용자 승인 없이 새로운 삭제 작업이나 범위를 생성하지 않습니다.
- 수동 재시도는 계정 요청당 최대 3회, 사유 10~280자, 재인증·브라우저 재확인, 고유 실행 ID로만 가능합니다. 중복 실행은 감사 이력을 통해 멱등 처리합니다.
- `gallipickAccountExitRequests/<owner>/adminHistory/<actionId>`에 관리자 UID **해시**, 사유, 재시도 단계, 시각을 기록하며 실제 삭제 파일의 원본 데이터는 감사 로그에 저장하지 않습니다.
- 실행 API `retryAdminDeletion`은 **`GALLIPICK_ADMIN_DELETE_RETRY_ENABLED=true`와 `GALLIPICK_ACCOUNT_EXIT_WORKER_ENABLED=true`가 모두 적용**돼야 동작합니다. 두 플래그는 기본 OFF이며 운영 배포 시 별도 승인과 검증이 필요합니다.
- 재시도 API 자체는 Storage·Auth·Firestore 사용자 데이터를 직접 삭제하지 않습니다. 실제 작업은 기존 사용자가 신청한 삭제 워커만 수행합니다.

## 구현 파일

- `firebase/functions/admin-deletion-review.js`: 관리자 전용 읽기·건별 조회·10건 사전 검토·기존 실패 요청 재시도
- `firebase/functions/admin-deletion-review.test.js`: 관리 권한, 일괄 비파괴 검토, 다른 UID·범위 보호, 10분 재인증, 감사 멱등성, 워커 비활성 차단
- `firebase/functions/index.js`: `getAdminDeletionReviews`, `previewAdminDeletionBatch`, `retryAdminDeletion` 인증 Callable 등록
- `firebase/admin-web/deletion-review.js`: 조회/페이지 이동/사전 검토/개별 기존 요청 재시도 화면
- `firebase/admin-web/index.html`, `firebase/admin-web/app.js`, `firebase/admin-web/styles.css`: 관리자 영역 연결 및 반응형 적용
- `.github/workflows/gp-admin-readonly.yml`, `scripts/qa/admin-hosting.test.mjs`: 정적 안전 검사 확장

## 남은 통합·운영 조건

1. 최신 GitHub CI 전부 성공 후 PR 병합. PR/main 중간마다 전체 Android 에뮬레이터 QA를 반복하지 않으며 개발 배치 마무리 후 1회 수행.
2. 운영 관리자 사용자·서버 IAM·Hosting 웹 앱 로그인 허용 도메인·수동 재시도 권한 검증.
3. 기존 사용자의 실제 원본 삭제 의사를 확인한 요청만 재시도하고 운영 감사를 검증. 보안 위협·미확인 구매·삭제 계정 경합 시 자동 재시도 활성화 금지.
4. 사용자 구독 만료 후 **14일 자동 삭제 실행**은 여전히 Play 최신 구독 재검증을 위한 안전한 구매 토큰 보관/재조회·운영 잠금·실제 Storage QA 선행 후 별도 개발.
5. 신규 계정 결제: 로그인 후 앱 결제 화면 진입 코드와 운영 서버 `GALLIPICK_PRODUCTION_BILLING_MODE=live`, 상품 게시 상태, 비테스터 구매 검증은 각각 별개의 점검 항목입니다. 코드상 진입은 가능하지만 실결제 전 계정/기기 QA 미확인.
6. 운영 Firebase Functions·Hosting, Google Play 업로드 및 실제 사용자 삭제는 사용자 별도 승인 전 미진행.

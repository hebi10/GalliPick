# GP-EXIT-03~05 — GalliPick 계정·클라우드 서버 삭제 구현 기록

기준일: 2026-11-20  
PR: https://github.com/hebi10/GalliPick/pull/148  
상태: GitHub PR 내 구현 및 합성 회귀 테스트. **운영 Firebase 미배포·삭제 미실행**.

## 1. 구현한 Functions

| Function | 유형 | 역할 |
|---|---|---|
| `requestAccountExit` | 인증 Callable | 명시적 동의·최근 Google 재인증(10분) 후 서버 요청 등록 |
| `getAccountExitStatus` | 인증 Callable | 본인의 `REQUESTED`, `IN_PROGRESS`, `RETRY`, `MANUAL_REVIEW`, `COMPLETED`, `RESUMED` 확인 |
| `resumeCloudAfterDeletion` | 인증 Callable | 클라우드 데이터만 삭제 완료 후 Google 재인증 및 사용자 명시 동의로 새 백업 재개 |
| `processAccountExits` | 별도 Scheduled Function | 요청된 계정의 검증된 원본·메타데이터 삭제 및 최종 Firebase Auth 탈퇴 |

사용자 API는 삭제할 UID·객체 경로를 직접 받지 않는다. Firebase 로그인 UID를 백엔드에서 SHA-256 해시하여 소유자를 식별한다. 다른 계정의 데이터나 다른 환경으로 삭제 범위를 확장하지 않는다.

## 2. 요청 프로토콜

- Scope `ACCOUNT` = 앱 계정 및 연결된 클라우드 전부 삭제; 확인 문구 `DELETE_ACCOUNT`.
- Scope `CLOUD` = 앱 계정/Play 구독 보존, 일반·시크릿 Cloud 원본만 삭제; 확인 문구 `DELETE_CLOUD`.
- 공통 확인: `acknowledgesPermanentDeletion=true`, `acknowledgesPlayCancellation=true` (구독 자동 갱신은 따로 취소해야 함을 인지).
- 서버에서 Google 제공자와 로그인 재인증 시간을 확인한다. 10분보다 오래된 로그인에는 새 Google 인증을 요구한다.
- 중복 동일 요청은 같은 상태를 반환하고 범위가 다른 진행 중 요청은 거부한다.
- 신규 요청은 `GALLIPICK_ACCOUNT_EXIT_ENABLED=true`와 `GALLIPICK_ACCOUNT_EXIT_WORKER_ENABLED=true`에 더해 유효한 `GALLIPICK_ACCOUNT_EXIT_ROLLOUT` 설정이 있어야 접수한다. 기본 상태에서는 새 접수와 삭제 실행이 모두 비활성이다.
- `pilot`은 `GALLIPICK_ACCOUNT_EXIT_PILOT_UID_HASHES`에 지정한 시험용 Firebase UID의 SHA-256 해시만 허용한다. 쉼표로 구분한 소문자 16진수 해시를 최대 10개까지 설정한다. 요청과 워커가 동일 허용 목록을 검사하며, 검증이 끝날 때까지 보류 중인 시험 계정의 해시를 유지한다.
- 일반 접수는 `general`을 명시하고 시험용 허용 목록을 비웠을 때만 가능하다. 장애 대응 시 접수 플래그를 끄면 기존 작업은 계속 처리할 수 있다. 파괴적 실행 자체를 정지하려면 워커를 비활성화한다.
- Android 요청·상태 화면은 PR #150에서 API와 연결했다. 운영 파일럿에서는 Play 서명 앱으로 접수·상태·재활성화가 동작하는지 확인한다.

## 3. 삭제 단계

1. `gallipickAccountExitRequests/<sha256(uid)>`에 REQUESTED 기록 생성. 이후 일반/시크릿 Cloud 및 결제 Callable 접근 거부, Firestore 업로드 quota 0 처리, RTDN 재부여 차단.
2. 기존 서명된 업로드 URL의 만료 시간을 감안한 약 20분 대기. 앱을 바로 차단하더라도 기발급 서명 URL은 만료 전까지 사용할 수 있어 '즉시 Storage 접근 100% 무효화'라고 안내하지 않는다.
3. `gallipick-production.firebasestorage.app` 버킷의 정확한 `cloud02/production/users/<owner>/`, `cloud02/license-test/users/<owner>/` 범위에서만 GCS 객체와 모든 세대(version) 삭제. 각 객체 삭제에 generation-match 조건 적용, 404만 멱등 무시, 전제 조건 위반은 실패 및 재시도.
4. 분할 업로드 잡과 일반·시크릿·v1·v2 Firestore 계정 경로를 `recursiveDelete`로 하위 컬렉션 포함 정리. 서버 전용 요청 제한·Vault Credentials도 사용자 범위에서 정리.
5. `ACCOUNT` 탈퇴에 한해 두 결제 모드의 계정별 구매권한·토큰 바인딩 정리. `CLOUD`만 삭제하면 구독과 로그인은 보존.
6. Storage 객체와 Firestore 계정 경로 및 작업 잡이 실제 비었는지 재확인. 완료 전까지 계정을 제거하지 않음.
7. `ACCOUNT`에 한해 Firebase Admin `deleteUser(uid)`를 마지막에 실행. 없는 사용자(이미 삭제)는 멱등 성공으로 처리.
8. COMPLETED 상태 저장 시 작업 기록에서 **원본 UID를 제거**하고 최소 상태·요청 시각만 유지. 개인정보보호·법적 보존·감사 로그 보존기간은 운영 승인 전 별도 확정 필요.

## 4. 안전·오류 처리

- 작업당 Firestore lease 5분, 40개 GCS 파일까지의 페이지별 순차 삭제, 4단계씩 진행 후 체크포인트 저장.
- 실패 시 중간 상태 및 원본 계정 인증을 보존하고 2분 뒤 재시도. 10회 연속 실패 후 `MANUAL_REVIEW`로 잠금/검토 전환. 일반 진행은 실패 횟수로 계산하지 않는다.
- 14일은 **운영 처리 목표 기한**이며, 관리자 수동 검토/네트워크 장애를 포함한 삭제 완료는 실제 서버 결과로만 판정한다. 작업 실패·진행 중을 완료라고 표시하지 않는다.
- `CLOUD` 삭제 완료 후 자동 백업이 원본을 다시 올리지 않게 서버 쓰기 권한과 할당량을 차단. 동일 계정의 재인증·명시 동의로 `resumeCloudAfterDeletion`을 호출할 때만 재활성화.
- 클라우드 `ACCOUNT` 삭제는 Play 구독 취소를 자동 대행하지 않음. Play에서 자동결제를 별도로 해지해야 한다는 경고가 필요하다. 기기에 남아 있는 Android 원본·휴지통은 삭제 대상이 아니다.
- 프로덕션에서 보유한 라이선스 시험과 실제 유료 환경은 동시 정리 대상이지만 `gallipick-dev` 별도 Firebase 프로젝트는 변경하지 않는다.
- 브라우저/Android 클라이언트는 Firestore·Storage 직접 삭제 권한이 없으며 서버 인증 경로만 이용한다.
- 운영 삭제 스케줄러는 전용 `gallipick-account-exit` 서비스 계정으로 실행한다. 배포 전에 이 계정을 구성하고 필요한 Firestore·Storage·Firebase Auth 권한만 부여한다. 삭제 기능을 운영에 적용할 때는 백업·결제 callable, 분할 업로드 워커, Play RTDN의 삭제 잠금 지원 버전을 함께 확인한다.

## 5. 합성 QA

Node test `account-exit.test.js`: 타 계정 보호, 최근 인증·명시적 확인, 요청 멱등성, 객체 세대/접두어 검증, Cloud-only 삭제/재시작, 계정 원본·metadata·Vault·job 삭제, Auth-last, 중간 실패와 재시도 검증.

Node test `cloud-billing.test.js`: 삭제 요청 중 업로드 할당량 0·Cloud API 차단·RTDN 권한 재부여 방지, Cloud-only COMPLETED 쓰기 차단 및 RESUMED 복구 확인.

## 6. 아직 필요한 작업

- **Android 운영 검증**: 서버 상태·Google 재인증·요청 API를 확인하고 오류·취소·다른 계정 전환 시 안전하게 처리되는지 검증.
- **운영 QA**: Firebase Emulator 및 합성 Storage/Firestore에서 실제 Admin SDK recursiveDelete·GCS version 삭제 검증. 운영 프로젝트 IAM은 필요한 최소 권한만 부여. Play 구독 갱신/환불/보류·기기 원본 보호 확인.
- **운영 기능 활성화**: 보안 검토·고객 고지·Play Console 계정 삭제 안내 확인 후 Functions를 배포한다. 전용 서비스 계정 권한과 시험 계정 허용 범위를 확인한 뒤 접수와 실행을 순서대로 활성화한다.
- **구독 종료 자동 만료 삭제**: GP-LIFECYCLE-02~03의 PURGE_DUE 대상과 이번 **사용자 직접 탈퇴 요청**은 별개. 7일 접근·14일 자동 삭제 스케줄러는 이후 독립 PR에서 안전하게 연결.
- **관리자 웹**: 처리 실패 MANUAL_REVIEW·작업 현황·사용자 요청 확인 및 최소권한 관리자 조치는 GP-ADMIN 후속 개발.

참고: https://firebase.google.com/docs/firestore/manage-data/delete-data · https://firebase.google.com/docs/auth/admin/manage-users · https://docs.cloud.google.com/storage/docs/samples/storage-delete-file

# GP-ADMIN-04 — 삭제 요청·오류·만료 후보 관리

기준일: 2026-11-20
상태: GitHub PR 구현 및 CI 검증 단계. **운영 Firebase/Hosting 미배포, 실제 사용자 데이터 삭제 미실행**.

## 구현 범위

1. 서버 `getAdminDeletionReviews`에서 회원이 이미 요청한 삭제 작업의 `REQUESTED`, `IN_PROGRESS`, `RUNNING`, `RETRY`, `MANUAL_REVIEW` 상태와 Google Play 재검증이 필요한 만료 후보를 각 최대 20건 조회합니다. 반환 항목은 SHA-256 회원 식별자, 삭제 범위, 접수·목표일, 작업 단계 및 실패 횟수입니다. 원본 UID, 이메일, 미디어 파일 목록, Play 구매 토큰은 반환하지 않습니다.
2. `previewAdminExpiryPurge`는 일괄 삭제 **사전 검토** 전용입니다. 검토 시 Firestore의 **현재 결제 기록/개정 버전/토큰 해시**, 같은 UID의 반대 결제 환경, 진행 중인 사용자 탈퇴, 관리자 지급 Cloud 이용권을 다시 확인합니다. 후보가 다시 유효하더라도 상태는 `NEEDS_PLAY_RECHECK`입니다. 반드시 `deletionAllowed=false`, `dryRunOnly=true`, `deletionExecuted=false`를 반환하며 자동 삭제 작업을 만들거나 Storage를 삭제하지 않습니다.
3. `retryAdminFailedDeletion`은 **사용자 본인이 기존에 접수했고 실패로 `MANUAL_REVIEW` 잠금된 삭제 작업 한 건**만 재시도하도록 예약합니다. 새 삭제 요청 생성 및 일괄 파일 삭제 API가 아닙니다.
4. 재시도는 Google Firebase 관리자 인증, `gallipickAdmin=true` 커스텀 클레임, 서버 UID 해시 허용 목록, 최근 10분의 Google 재인증, 일치하는 회원 식별자·기존 삭제 요청 ID·12~280자 사유·명시 확인을 요구합니다.
5. Firestore 트랜잭션에서 해당 계정의 삭제 요청 범위·소유자 해시·`MANUAL_REVIEW` 상태·작업 단계를 확인하고 `RETRY`로 되돌립니다. `requestId` 멱등키와 별도 `gallipickAdminDeletionReviewAudits` 감사 로그를 함께 기록합니다. 사용자 원본 파일을 직접 삭제하지 않고 기존 보호된 서버 스케줄러가 후속 처리합니다.
6. 운영 환경 변수 `GALLIPICK_ADMIN_EXIT_RETRY_ENABLED=true`와 기존 `GALLIPICK_ACCOUNT_EXIT_WORKER_ENABLED=true`가 **둘 다 설정된 경우에만** 수동 재시도 기록을 수락합니다. 기본값은 모두 OFF입니다.
7. UI는 이미 존재하는 Firebase Hosting 관리자 웹의 동일 흑백·회색 디자인, 모바일 반응형에 맞춰 삭제 처리 상태·미리보기·관리자 재시도 폼을 배치합니다. 삭제 상태는 별도 확인해야 하며 재시도 접수를 완료 처리로 표시하지 않습니다.

## 보안 및 개인정보

- 서버 관리자 권한 검사는 매 호출마다 수행합니다. 클라이언트 임의 Firestore 경로, 신규 사용자 삭제 지시, 일괄 객체 삭제, Play API 임의 토큰 조회는 허용하지 않습니다.
- 조회 결과는 각 목록 최대 20건의 부분 조회입니다. 현재 전체 신청/삭제 건수와 혼동하지 않으며, 더 많은 기록이 있으면 표시합니다.
- 감사 기록은 계정과 관리자 모두 UID 해시, 작업 ID, 작업 사유/시각만 담습니다. 보존 기간·감사 이벤트에 대한 접근과 운영 콘솔 IAM은 배포 전 별도 확정이 필요합니다.
- 이미 Play에서 갱신된 고객이 만료 후보에 있으면 재검토 결과는 삭제 보류입니다. Play 구매 토큰 해시만으로 실제 최신 결제를 재조회할 수 없어 `NEEDS_PLAY_RECHECK`를 삭제 실행 권한으로 해석하지 않습니다.
- 위험한 일괄 영구 삭제는 이번 PR에 포함하지 않습니다. 실사용자 원본의 복원 불가 조치는 별도 운영 검증 및 승인 전 수행하지 않습니다.
- Firebase Storage/Firestore/Auth 데이터, Google Play 결제 상태와 실제 가입 계정은 테스트/코드 작성 과정에서 수정하지 않습니다.

## 검증

- Node Cloud backend: 비인가 관리자 차단, 회원별 조회 개인정보 비노출, 20건 제한, 삭제 후보의 Play 갱신/수동 Cloud 혜택 보호, 누락·변경된 이용권 기록 보류, 동일 삭제 요청 재시도 멱등성과 변경 사유 감사, 잘못된 요청 및 비활성 운영 플래그 차단.
- GP-ADMIN Hosting 검증: Firebase 서버 Callable만 사용하고 HTML 문자열 삽입/Firestore 직접 접근/대량 삭제 호출이 없음을 점검합니다.
- Android verification/OPS-07/09 등 기존 필수 검사가 성공한 정확한 HEAD만 안전 병합합니다.

## 다음 선행 작업

- GP-ADMIN-04 운영 검증: Firebase Auth 관리자 실제 권한·IAM·Firestore audit 보존기간, 사용자 삭제 요청 후 합성 Storage/Firestore 재시도 종단 QA.
- GP-LIFECYCLE-DELETE 후속: 구매 토큰 안전 보관 및 삭제 직전 Play Developer API 구독 재검증, 결제 갱신 경합 제어, 후보별 정확한 삭제/철회 조건.
- 신규 가입자 결제: 앱 로그인/화면 진입과는 별도로 **서버가 실제 live 모드인지, Google Play 상품과 결제 계정이 구매 가능한지** 확인. 운영 서버 설정은 코드만으로 확인할 수 없습니다.
- UI 및 QA 통합 완료 후에만 사용자 승인을 받아 Functions/Hosting/Google Play 배포. 기존 AGENTS.md의 수동 승인 정책을 유지합니다.

# GP-ADMIN/LIFECYCLE/EXIT — 관리자·클라우드 7일 복원·14일 삭제 정책 (2026-11-20)

> 상태: **설계 + 제한적 UI/정책 코드 준비**. 관리자 운영 웹과 실제 서버 삭제·스케줄러는 아직 구현/배포되지 않았다. 운영 Firebase, 유료 구독, 데이터는 변경하지 않는다.
> 관련 PR: https://github.com/hebi10/GalliPick/pull/146
> 기존 계획: https://docs.google.com/document/d/1nk9Fos-Usgd968mqbtWX84P8K1pZBQELE6RKRD7BXt8/edit

## 결정 원칙

1. 앱 내 클라우드 Google 로그인은 클라우드 개요에 남는다. 요금제 화면 진입은 사용자의 명시적 선택이다.
2. Google Play **자동 갱신 취소일**과 **이미 결제한 이용기간 종료일**은 별도다. 구독 취소 직후 7일 만에 접근을 막으면 이미 유효한 이용권을 박탈할 수 있다. 시작일은 반드시 Google Play subscriptionsv2.get으로 검증한 **유료 이용기간 종료일**이다.
3. 유료 클라우드 기간 종료 직후부터 7일간 기존 클라우드 사진·영상(시크릿 포함)을 읽고 다운로드·복원할 수 있다. 신규 업로드 및 자동 백업은 막는다.
4. 종료 후 7일째부터 14일째까지 원본 열람·다운로드·복원 차단, 서버는 삭제 대기 상태와 예정일을 반환한다.
5. 종료 후 14일째부터 소유자만을 대상으로 서버 삭제 작업을 실행한다. 정각에 삭제가 완료됐다고 추정하지 않는다. 실패한 항목은 재시도·알림·수동 처리 대상이다.
6. 재구독이 서버에서 검증되어 삭제 작업이 시작되기 전이면 삭제 예약 취소. 삭제 실행 시작 후에는 이전 원본이 복원된다고 보장하지 않으며 사용자에게 알린다.
7. **회원 탈퇴 요청**은 별도다. 접수·본인 재확인 후 클라우드 접근을 즉시 차단하고 가능한 즉시 삭제; 최대 14일 안에 처리를 목표로 한다. 탈퇴 요청에는 7일 복원 유예를 주지 않는다. 실제 삭제 확인 후 Firebase Authentication 계정을 삭제한다.
8. Google Play 구독을 앱에서 삭제·취소한 것처럼 조작하지 않는다. 회원 탈퇴와 Play 자동결제 종료는 별도다. 필요 시 Play Developer API의 subscriptionsv2.cancel을 사용하고 취소·환불·즉시 회수(revoke)를 구분한다.
9. 기기 로컬 사진, Android 갤러리/휴지통은 클라우드 계정·데이터 삭제와 독립이며 어떤 경우에도 자동 삭제하지 않는다.
10. **광고 제거 단독 구독**에는 저장공간과 클라우드 보존 타이머를 부여하지 않는다. 데이터가 없는 계정에 삭제 예약을 만들지 않는다.

## 서버 상태·데이터 설계

별도 `cloudAccountLifecycle` 문서에 UID 해시로 소유자 식별. 서버 전용 필드: project, environment, lastVerifiedCloudProductId, lastVerifiedCloudExpiryMs, autoRenewing, lifecycleState, recoveryEndsAtMs, purgeDueAtMs, deletionRequestedAtMs, deletionScope, deletionStatus, updatedAtMs, revision. 사용자 ID는 SDK 임의 입력을 수용하지 않고 검증된 Firebase Auth UID를 서버에서 해시한다.

상태: `PAID` → `READ_ONLY_RECOVERY` → `LOCKED_AWAITING_PURGE` → `PURGE_DUE` → `PURGING` → `PURGED`; 별도 명시 탈퇴에는 `ACCOUNT_DELETION_REQUESTED` → `PURGING` → `PURGED`. 오류는 `RETRY_REQUIRED`, 수동 점검은 `MANUAL_REVIEW`. 관측 데이터가 없으면 `UNKNOWN`으로 닫고 날짜를 만들어내지 않는다.

서버에서 현재 구독이 없는 것만으로 바로 삭제하지 않는다. 과거에 검증된 Cloud 구독 만료 시점이 durable하게 남아 있어야 시작할 수 있다. RTDN은 변경 신호일 뿐이며 Play API 재조회 결과로 날짜와 권한을 업데이트한다. 갱신·구매 복원·계정 연결 변경을 같은 엔드포인트에서 검증한다.

클라이언트 응답 제안: lifecycleState, paidThroughMs, recoveryEndsAtMs, purgeDueAtMs, mayRead, mayDownload, mayUpload, verificationStatus, lastCheckedAtMs. 기존 `CloudBillingPlan`·`CloudBackupAccess`에 선택적 필드로 추가하되 실패 시 권한을 확대하지 않는다.

서버 검사 범위: `getCloudPlan`, `beginBackup`, `finishBackup`, `listBackups`, `getBackupDownload`, `getBackupPart`, `getBackupStatus`, `secretCloud`와 이어받기 업로드 경로·서명 URL 발급 전부. 이미 발급된 짧은 유효기간 URL은 취소 요청 후 남을 수 있으므로 공개 UX에서 즉시 무효라고 주장하지 않는다.

## 관리자 웹

Firebase Hosting에 GalliPick 전용 독립 admin 사이트를 만들고 운영 Firebase와 동일 프로젝트의 보안 검증된 API에 연결. 개발/운영은 별도 Firebase 프로젝트 및 배포 대상을 쓴다. `firebase.json`에는 현재 Hosting 설정이 없으므로 admin만을 위한 명시적 hosting target 및 분리된 빌드 디렉터리를 추가한다. Firebase Hosting 생성/게시 자체는 이번 PR에 포함하지 않는다.

화면 A — 대시보드: 총 로그인 계정, 활성 구독(Play 검증), Cloud 총 사용량, 만료/복원 기간/삭제 예정/실패 건수. 모든 수치는 서버 확인 시각 표시.
화면 B — 회원 조회: UID 해시 기반 조회, 필요할 때만 이메일 표시, 로그인/활성/탈퇴 상태, 해당 사용자 소비 용량 및 처리 이력. 사진·영상 원본 관리자 미리보기는 기본 제공하지 않는다.
화면 C — 구독 관리: Play productId/basePlanId·자동 갱신·서버 검증 만료일, 확인 가능한 요금제 전환/취소 상태. 운영자가 Play 구매를 Firestore 변경으로 덮어쓰지 않는다.
화면 D — 수동 이용권: 별도 운영 무상 체험/수동 보정 entitlement(명확한 만료일·사유·승인자). 실제 구매와 목록/상태를 분리하고 서버에서 권한 결합 정책을 검증한다.
화면 E — 클라우드/삭제 예정: 0~7일 복원·7~14일 잠금·삭제 처리 중·삭제 실패 계정별 목록, 처리대상 건수/바이트, 정책 날짜.
화면 F — 요청·감사 로그: 회원 탈퇴/클라우드 데이터만 삭제, 수동 처리, 실패 재처리, 관리자 작업자·대상 해시·사유·시각·결과. 구매 토큰·서명 URL·원본 파일명·암호는 감사 로그에 기록하지 않는다.

관리 권한: Firebase Authentication 관리자 계정 + 서버에서 설정한 `admin` custom claim과 서버 측 허용 목록을 함께 확인. 세션 만료·최근 재인증·다중 인증·App Check·관리 서비스 계정 최소 권한을 적용. 프런트엔드 Hosting 주소만 숨겨 접근을 통제하지 않는다. Firestore 직접 전체 읽기 권한을 브라우저에 주지 않는다.

대량 삭제: dry-run으로 대상 계정·예정일·건수·바이트 요약 → 현재 Play 이용권 재검증 → 2단계 확인(운영자 재인증과 삭제 대상 범위 재확인) → 서버 작업 ID 발급 → 제한된 배치·중복 실행 방지 → 성공/실패 항목별 검증. 정상 유료 계정·다른 계정·다른 환경의 객체 삭제는 거부한다.

삭제 워커는 Firebase Scheduler/Cloud Tasks 등 서버 실행기로 구현. 무중단/실패 복구, 예약 재시도, 작업 잠금·revision, 제한된 삭제 건수, 모니터링/알림이 필수. Storage 객체(일반/시크릿/분할 파일·중단 작업), Firestore 원본·예약·할당량·VaultCredentials, Firestore lifecycle 및 Auth 삭제의 일관된 순서를 검증한다.

## 앱 UI 설계

클라우드 첫 화면 — 로그인 상태가 `CHOOSING_ACCOUNT` 또는 `SIGNING_OUT`이면 원래 내용을 감추고 회색 원형 스피너와 'Google 계정으로 로그인하는 중입니다.' / '클라우드 계정에서 로그아웃하는 중입니다.' 텍스트를 즉시 표시한다. 타이머로 강제 3초 유지하지 않는다. Firebase 응답이 끝나면 바로 전환한다.
구독 취소 후 유료기간 진행 중 — '자동 갱신이 취소되었습니다. 구독 혜택은 YYYY.MM.DD까지 유지됩니다.' 원본 삭제 예고를 과도하게 앞당기지 않는다.
유료기간 종료~7일 — '클라우드 구독이 종료되었습니다. YYYY.MM.DD까지 기존 사진·동영상 다운로드 및 복원이 가능합니다. 새로운 백업은 중단됩니다.'
7~14일 — '복원 가능 기간이 끝났습니다. YYYY.MM.DD에 클라우드 데이터가 삭제될 예정입니다.' 다운로드·재생 버튼 비활성.
14일 이후 — 서버가 삭제 완료 확인 전에는 '삭제 처리 중' 또는 '삭제에 문제가 있어 확인 중'으로 표시한다. 완료 확인 후 '클라우드 데이터 삭제 완료'.
정확한 날짜는 서버에서 검증된 값만 사용하고, 데이터 없음/서버 오류인 경우 카운트다운·완료 메시지를 표시하지 않는다.
회원 탈퇴 — 로그인한 경우 클라우드 탭 최하단과 설정 탭 최하단에 공통 '회원 탈퇴' 버튼. 모달 제목 'GalliPick 회원 탈퇴'; 현재 구독 상태 확인 중/유효/확인 불가를 구분한다. 원본·시크릿·다른 기기 접근 즉시 중단, 최대 14일 내 서버 삭제 계획, 삭제 완료 후 복원 불가, 구독 자동 갱신은 Google Play에서 별도 취소, 단말 원본 유지 내용을 표시한다. 실제 서버 제출 연결 전에는 버튼을 '탈퇴 요청 메일 작성'으로 정확하게 표현한다.
클라우드 데이터만 삭제 — 탈퇴와 별도 API/요청. 회원 유지·구독 유지·기기 원본 유지. 별도 영구 삭제 확인 모달과 실행 상태를 가진다.

## 단계별 작업·완료 조건

| 순서 | ID | 작업 | 검증/완료 조건 |
| --- | --- | --- | --- |
| 1 | GP-UI-LOADING-01 | Google 로그인/로그아웃 진행 표시 | 계정 선택 중 스피너·문구 표시, 취소/오류·성공 후 잔상 없음 |
| 2 | GP-EXIT-UI-02 | 클라우드·설정 최하단 회원 탈퇴 모달 | 구독 상태 구분, 요청 vs 완료 오인 없음, 로그인 후에만 표시 |
| 3 | GP-LIFECYCLE-01 | 서버 기간/상태 계산 계약 | 유료기간 종료 기점 7·14일 경계, 결제 갱신·탈퇴 별도, 합성 테스트 |
| 4 | GP-LIFECYCLE-02 | Play RTDN·구독 이력 상태 보존 | 갱신·만료·유예·회수·환불 후 상태/날짜 일치 |
| 5 | GP-LIFECYCLE-03 | 전 클라우드 API 읽기·쓰기·복원 게이트 | read-only/locked/account-deletion 차단, 다른 UID/기기 접근 거부 |
| 6 | GP-EXIT-03 | 사용자 삭제 요청/재인증 API | 본인 UID만, 중복 안전, 삭제 잠금, 구독 중에도 요청 허용 |
| 7 | GP-EXIT-04 | 서버 Storage·Firestore 일괄 삭제 | 일반/시크릿/v2 예약·객체 세대 검증, 멱등·실패 재시도 |
| 8 | GP-EXIT-05 | Auth 탈퇴 및 처리 결과 | 서버 데이터 삭제 후 Auth 삭제, 처리 중 재로그인/업로드 차단 |
| 9 | GP-ADMIN-01 | 별도 Firebase Hosting 관리자 셸 | 도메인·admin claim·서버 API deny-by-default·테스트 환경 분리 |
| 10 | GP-ADMIN-02 | 회원/구독/저장공간 조회 | Play 상태와 서버 이용권 표시 분리, 개인정보 노출 최소화 |
| 11 | GP-ADMIN-03 | 수동 이용권·Play 취소 관리 | 허용된 관리 동작만 호출, 실결제/수동 grant 분리, 감사 기록 |
| 12 | GP-ADMIN-04 | 삭제 예약·일괄 정리 | dry-run, 2단계 확인, 활동 중/다른 계정 삭제 방지, 작업 결과 |
| 13 | GP-LIFECYCLE-04 | 클라우드 복원/삭제 예정 날짜 UI | 서버 날짜 기반 기간별 문구, 타이머 오차/오프라인 안전 |
| 14 | GP-EXIT-QA | 전체 QA·정책/콘솔 정합성 | Firebase Emulator→합성 Storage→Play 라이선스 테스트→계정 삭제 웹 링크·Data safety 확인 |

현재 PR #146 변경 사항은 1~3단계 일부: 로그인 표시, 회원 탈퇴 확인 모달/메일 연결, 서버 7·14일 **순수 계산 함수 및 테스트**. 서버 접근 차단/실제 원본 삭제는 연결하지 않아 고객 데이터에 영향 없음.

## 운영 승인·배포

- `main` 병합은 PR 필수 CI 성공 확인 후. 전체 Android 계측은 기능 개발 배치가 끝난 뒤 최신 정확한 `main` 커밋에 대해 1회.
- Firebase Hosting 운영 관리자 사이트, Firebase Functions/Firestore Rules 배포는 코드 검증 후 사용자 승인·대상 프로젝트 확인 뒤 수행한다.
- Google Play 서명 AAB·내부 테스트 배포는 전체 배치 QA 후 사용자가 승인할 때만 기존 OPS-09 파이프라인으로 수행한다. 이 문서 자체는 어떤 배포 승인도 아니다.
- 기존 데이터에 대한 14일 기산점 없이 자동 삭제를 켜지 않는다. 먼저 과거 이용권/만료 기록 마이그레이션·불일치 검증·dry-run을 완료한다.
- 취소/만료 후 7일 복원 기간이나 14일 보존 정책을 이미 운영 중이라고 표시하지 않는다.

## 공식 근거

- Google Play subscription lifecycle: https://developer.android.com/google/play/billing/lifecycle/subscriptions
- Google Play account deletion: https://support.google.com/googleplay/android-developer/answer/13327111
- Firebase Hosting multisite: https://firebase.google.com/docs/hosting/multisites
- Firebase Auth admin claims: https://firebase.google.com/docs/auth/admin/custom-claims
- Firebase scheduled functions: https://firebase.google.com/docs/functions/schedule-functions

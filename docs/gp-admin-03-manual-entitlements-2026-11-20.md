# GP-ADMIN-03 — 관리자 수동 이용권·회수·감사 로그

기준일: 2026-11-20
상태: 구현 및 PR 검증 단계. 운영 Firebase·Hosting·Play 미배포.

## 목표

Firebase Hosting 관리자 화면에서 로그인 회원을 선택하고 Google Play 유료 결제를 수정하지 않는 **별도 무상 체험/보상 이용권**을 기간 제한으로 지급하고 회수합니다.

## 관리 대상

- `CLOUD_10GB` : Cloud 10 GB + 광고 제거
- `CLOUD_50GB` : Cloud 50 GB + 광고 제거
- `CLOUD_100GB` : Cloud 100 GB + 광고 제거
- `AD_FREE` : 광고 제거만, Cloud 저장공간 0 GB

수동 이용권의 지급 기간은 1~90일입니다. 월·연 자동 갱신이 아니고, Google Play의 실제 결제·구독 상태를 편집하지 않습니다. 수동 Cloud 기간 만료 후 최대 7일간 이미 보관 중인 파일의 읽기·복원만 허용하며 업로드는 중단합니다. 이후 실제 자동 삭제가 확정되지 않은 고객 파일은 안전하게 차단·검토합니다. 운영자가 이용권을 **회수**하면 읽기·쓰기와 광고 제거 혜택이 즉시 중지되며, 원본을 즉시 삭제하지는 않습니다.

## 백엔드 구조

- `gallipickAdminManualGrants/<mode>-<sha256(uid)>`: 현재 운영자 지급 기록 1건. `mode`는 `live` 또는 `license-test`만 허용하며 개발 프로젝트와 교차되지 않습니다.
- `gallipickAdminManualGrants/<mode>-<owner>/history/<requestId>`: 작업자 UID 해시, 수혜자 UID 해시, 지급/회수 사유·시각·상품·이전/현재 revision을 포함하는 변경 이력.
- `GALLIPICK_ADMIN_ALLOWED_UID_HASHES`: 서버에서 등록한 관리자 Firebase UID SHA-256 허용 목록.
- `gallipickAdmin=true` Firebase Authentication custom claim 및 검증된 Google 계정, 최근 10분 재인증을 모두 확인한 뒤 변경합니다.
- 계정 탈퇴 진행 중에는 이용권을 지급하지 않습니다. 동일 활성 이용권은 임의 덮어쓰지 않고, 회수 후 지급하거나 만료 후 갱신합니다.
- `expectedRevision`을 통한 동시 변경 충돌 차단 및 32자리 무작위 요청 ID로 중복 요청을 멱등 처리합니다.
- 갱신·회수 실패는 서버 오류로 반환하며 UI에서 완료로 표시하지 않습니다. 감사 기록에는 원본 Google 이메일·Firebase UID·Play 구매 토큰·미디어 URL을 저장하지 않습니다.

## API

- `getAdminManualGrant`: 관리자 전용 현 이용권 및 최대 최근 12건 감사 기록 조회.
- `issueAdminManualGrant`: 회원, 환경, 상품, 1~90일, 사유, revision, 명시 동의 확인 후 지급.
- `revokeAdminManualGrant`: 해당 회원·환경의 현 이용권을 사유와 함께 회수.
- 모든 읽기/쓰기에서 서버 관리자를 확인합니다. 클라이언트에서 Firebase Firestore에 직접 쓰지 않습니다.
- `GALLIPICK_ADMIN_MANUAL_GRANTS_ENABLED=true`: 신규 지급/회수 변경을 허용. 기본값 **비활성**.
- `GALLIPICK_PRODUCTION_MANUAL_GRANTS_ENABLED=true`: 운영 앱의 실제 수동 혜택 적용. 기본값 **비활성**. **Cloud가 활성인 환경에서는 `GALLIPICK_PRODUCTION_CLOUD_RETENTION_ENABLED=true`도 먼저 검증되어 있어야 하며**, 아니면 Functions 설정을 거부하도록 안전 차단합니다. 운영 Cloud 자체를 허용하지 않는 설정에서는 수동 이용권도 Cloud 업로드를 켜지 못합니다.

## 앱 권한 연결

실제 Google Play 구독과 관리자 지급 이용권은 **별도 필드**입니다.

- Play 구독: 기존 `activeProductId` / `adFreeProductId`, 구매 검증 토큰 해시·서버 갱신 프로토콜을 보존합니다.
- 수동 지급: `manualGrantKind`, `manualGrantExpiresAtMs`, `manualGrantQuotaBytes`, `manualAdFreeExpiresAtMs`로 응답합니다. 앱은 알려진 10/50/100 GB, AD_FREE만 수용합니다.
- 이용자의 Cloud 업로드 한도는 유효한 Play 저장공간과 유효한 수동 Cloud 한도 중 큰 값만 적용합니다(용량 합산 금지). 할당량 검사는 서버 Firestore transaction 내에서 수행하고 앱은 서명된 Functions 결과만 사용합니다.
- 정상 Play 구독 없이 임시 Cloud 이용권만 있어도 명시 승인된 운영 스위치가 활성일 때 클라우드 백업에 접근할 수 있습니다. 수동 이용권 만료·회수 시 그 혜택은 중단됩니다. 다른 실제 구독이 살아 있다면 그 구독의 효력은 유지됩니다.
- 관리자 지급 Cloud 기간의 읽기 연장과 실제 Play 유료기간 종료의 7일 복원·14일 삭제 정책은 구별합니다. 실제 파일 삭제는 별도 Play 재조회 없이 실행하지 않습니다.
- 앱 결제 페이지에는 '관리자 지급 이용권'과 무상 기한을 별도 안내합니다. Play 구매 화면의 정기결제 항목과 혼동하지 않습니다.

## 검증 및 운영 전제

- Cloud backend Node 테스트: 무단 관리자 접근, 다른 계정/환경 분리, 기간 상한, 멱등 요청, revision 충돌, 지급·회수 후 감사로그, 만료/7일 복원/회수 차단, Cloud+Play 공존, 광고 제거만 지급, feature flag 기본 off.
- Android Kotlin 테스트: 수동 이용권을 Play 구매처럼 취급하지 않는 정책, 수동 Cloud 업로드 기한/용량, 광고 제거 적용/만료.
- Hosting UI 정적/권한 QA: Google 재인증, 2단계 명시 확인, 외부 HTML 삽입 금지, Play 구매/원본 삭제 API 미호출.
- 실제 운영 적용은 Firebase Functions/Hosting 별도 배포 승인, 관리자 로그인/권한 설정, Play 구매 QA, 실기기 통합 QA를 통과한 뒤. 두 운영 플래그는 명시 승인 없이 활성화하지 않습니다.
- 기존 고객의 실제 Play 청구 금액·환불·취소는 Google Play Console 및 Play Developer API에서만 변경합니다.

## 다음 작업

GP-ADMIN-04: 탈퇴·삭제 예약 및 관리자 오류 처리 화면. GP-LIFECYCLE-DELETE: 만료 후 자동 삭제는 안전한 Play 최신 구독 확인 경로가 마련된 뒤 별도 검증. 최종 앱/Cloud/Hosting 통합 QA와 운영 승인 후 단계별 배포.

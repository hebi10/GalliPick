# GP-ADMIN-02 — 회원별 구독 및 클라우드 저장공간 조회

기준일: 2026-11-20
상태: GitHub PR에서 구현·검증. 운영 Firebase/Hosting 미배포.

## 구현

- **Firebase Authentication 가입 계정**을 `listUsers(15, pageToken)` 방식으로 15명씩 조회합니다. Firebase Auth에 존재하지 않는 과거 결제 레코드는 회원 목록에 포함하지 않고, 상단 기존 운영 계정 기록 수로 별도 유지합니다.
- 모든 조회는 서버에서 `gallipickAdmin=true` 커스텀 클레임, Google 인증·검증 이메일, UID SHA-256 허용 목록, 최근 로그인 시간 조건을 재검사합니다.
- 사용자가 UID나 Firestore 경로를 요청 파라미터로 임의 지정할 수 없습니다. 페이지 토큰만 지정할 수 있으며 길이/형식을 검사합니다.
- Firebase Auth UID는 서버에서 SHA-256으로 변환하여 `gallipickProductionBillingAccounts`, `gallipickProductionLicenseBillingAccounts` 및 같은 계정 해시의 Cloud 할당량 문서만 조회합니다.
- 응답은 **해시된 계정 식별자**, 마스킹 이메일, 가입/최근 로그인 시각, 계정 비활성 여부, 구독 정보와 삭제 요청 상태만 포함합니다.
- 실결제와 라이선스 시험은 각각 상품 ID/base plan ID, 검증된 유효 이용권 종료시각, 검증 시각, 클라우드 저장공간 할당량과 사용량, 보존/복원 기간을 별개로 표시합니다.
- `verifiedMonetizationBenefits`의 서버 승인 상품 카탈로그만 사용하여 활성 제품을 평가합니다. **결제 기록/서버 유효기간은 Google Play 실시간 자동갱신 상태가 아닙니다.**
- 사용량 `allocatedBytes`는 업로드 예약을 포함할 수 있으며, 문서가 없는 경우 0으로 추측하지 않고 '확인 불가'로 표시합니다.
- 사용자 이메일 전체, Firebase Auth UID, 원본 구매 토큰/토큰 해시, 파일 이름·미디어 URL·개인 사진은 서버 응답과 관리자 페이지에 제공하지 않습니다.
- 관리 화면은 블랙/회색 평면 반응형 구조를 유지하고, 회원별 두 환경의 상품/사용량을 한눈에 비교하며 이전·다음 페이지와 새로고침을 제공합니다.
- 서버 및 프런트 모두 **읽기 전용**입니다. 결제 변경·백업 삭제·사용자 계정 제거 API 호출은 추가하지 않았습니다.

## 구현 파일

- `firebase/functions/admin-members.js`: 서버 인증/페이징/구독·저장공간 평가
- `firebase/functions/admin-members.test.js`: 권한 불일치, 다른 UID 접근, 실제 상품 검증, 만료, 잘못된 계정 데이터, 페이징, 개인정보 누출 방지 테스트
- `firebase/functions/index.js`: `getAdminMembers` callable endpoint
- `firebase/admin-web/index.html`, `firebase/admin-web/app.js`, `firebase/admin-web/styles.css`: 관리자 회원별 조회 화면
- `scripts/qa/admin-hosting.test.mjs`: UI 정적/읽기 전용 검증

## 다음 작업 및 배포 제한

1. PR 백엔드·Android·Hosting·OPS CI 검증 후 안전 병합.
2. Firebase Auth 관리자 조회 권한(`firebaseauth.users.list` 등)을 운영 서비스 계정에 최소 범위로 부여할 수 있는지 점검; 사이트·Function 공개 전 비인가/일반 계정 접근 거부 테스트.
3. 현재 저장공간 수치는 서버 할당량 기록입니다. 클라우드 실제 GCS 객체 크기 합산, 보관 파일 개수 집계·정산은 운영 사용량 검증 후 별도 구현.
4. 결제 자동 갱신·취소·환불의 **실시간 확인**은 Google Play Developer API와 서버 구매 토큰의 안전한 검증 경로를 완료한 뒤 추가합니다.
5. GP-ADMIN-03 수동 이용권은 Play 구독과 분리하고 승인자·사유·만료일·감사 로그로 통제합니다.
6. GP-ADMIN-04 삭제 요청·대량 삭제 현황은 Play 재검증·기존 고객 이력 점검 후 실행권한을 별도로 분리합니다.
7. 운영 Firebase Hosting·Cloud Functions 배포 및 Google Play 앱 업로드는 이번 PR에 포함하지 않습니다.

공식 참고: https://firebase.google.com/docs/auth/admin/manage-users

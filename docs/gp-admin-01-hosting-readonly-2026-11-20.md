# GP-ADMIN-01 — Firebase Hosting 관리자 웹 기본 구성

기준일: 2026-11-20
상태: 읽기 전용 관리자 셸, 서버 권한 검사 및 CI. **운영 Hosting·Functions 미배포**.

## 구현

- `firebase.json`에 독립 Hosting target `gallipick-admin`, 정적 공개 경로 `firebase/admin-web`을 추가합니다. 기존 Functions/Firestore/Storage 구성을 변경하지 않습니다.
- `firebase/admin-web`에 반응형 관리자 인증 화면과 조회 대시보드를 제공합니다. 블랙·중성 회색, 평면 UI, 접근 가능한 버튼/상태문구 사용.
- Firebase Web SDK v13.4.0에서 Google 팝업 로그인 후 `getAdminOverview` Callable Function을 호출합니다. 클라이언트는 Firestore에 직접 연결하거나 결제·원본 삭제 API를 호출하지 않습니다.
- `firebase/functions/admin-overview.js`는 운영 프로젝트에서 다음 세 조건을 모두 확인합니다.
  1. Firebase Authentication으로 확인된 Google 제공자 및 인증 이메일
  2. Firebase Admin SDK로 부여한 서버측 `gallipickAdmin===true` 커스텀 클레임
  3. 운영 환경 변수 `GALLIPICK_ADMIN_ALLOWED_UID_HASHES`에 포함된 해당 UID의 SHA-256 해시
- 마지막 Google 재인증이 24시간을 넘으면 관리자 서버 조회를 거부합니다.
- 조회 항목: 운영 실결제 계정 **기록 수**, 라이선스 시험 계정 기록 수, 삭제 진행/대기 건수, 삭제 수동 검토 건수, Google Play 재검증 대기 건수.
- 활성 유료 구독자 수와 결제 기록 수를 혼동하지 않도록 안내합니다. 개인 이메일·사진·영상·구매 토큰은 반환하지 않습니다.
- `scripts/qa/admin-hosting.test.mjs`, `.github/workflows/gp-admin-readonly.yml`, `firebase/functions/admin-overview.test.js`에서 설정·권한·응답 정합성을 검증합니다.

## 실제 Hosting 사용 전 준비

이 단계에서는 아래 운영 리소스의 생성·수정을 **수행하지 않았습니다**.

1. Firebase Console에서 `gallipick-production`용 보조 Hosting 사이트를 생성하고, 선택한 SITE_ID를 `gallipick-admin` target에 매핑합니다.
2. 운영 Firebase 프로젝트에 관리자 웹 앱을 등록하고 발급된 **공개 웹 앱 구성**만 `firebase/admin-web/config.js`의 빈 값에 적용합니다. 실제 API key/앱 ID의 허용 도메인과 Firebase 인증의 승인된 리디렉션 도메인을 확인합니다. 서버 계정 비밀번호·서비스 계정 키·Play 구매 토큰은 HTML/JS/Git에 넣지 않습니다.
3. Google 인증 제공자를 확인하고 허용된 관리자 Firebase Auth UID에만 `gallipickAdmin` 커스텀 클레임을 부여합니다.
4. 관리 권한을 부여받은 UID의 해시만 서버 환경 변수 `GALLIPICK_ADMIN_ALLOWED_UID_HASHES`에 등록합니다. 비인가·서로 다른 UID·유효기간 초과 테스트를 통과해야 합니다.
5. `getAdminOverview` Function 및 Hosting만 별도 승인 후 배포하고, 정상 접근·차단·모바일 반응형·Cloud Functions 권한을 실제 사이트에서 점검합니다.

별도 Hosting target의 공식 예시:

```bash
firebase target:apply hosting gallipick-admin <SITE_ID> --project gallipick-production
firebase deploy --only hosting:gallipick-admin --project gallipick-production
```

위 명령어는 **문서상 참고 절차**이며 현재 실행하거나 실제 SITE_ID를 생성하지 않았습니다.

## 관리자 도구 후속

- GP-ADMIN-02 — 회원/구독/할당량 조회(개인정보 최소 노출), Play 검증 만료 시점 및 기록/활성 구분.
- GP-ADMIN-03 — 운영자 임시 체험권과 실제 Play 구매의 분리, 변경 전 재인증·감사로그.
- GP-ADMIN-04 — 삭제 요청/재시도/Play 재확인·일괄 삭제 대상 검토. 승인 없는 일괄 삭제 버튼 배제.
- GP-LIFECYCLE-DELETE — 현 단계는 서버에 **삭제 후보 검토용 기록만** 작성. 실결제 상태 재검증과 삭제 실행은 별도 승인·QA 전 금지.
- **운영 Functions와 Play 배포는 이번 코드 작업에 포함되지 않음.**

공식 문서: https://firebase.google.com/docs/hosting/multisites

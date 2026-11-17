# CLOUD-UI-00 — 클라우드 상태·라우팅·공통 UI 기반

기준: GalliPick `main` (2026-11-17), [CLOUD-UI 계획](https://docs.google.com/document/d/1z-AAAsj4-L9mYJdWzrF_3Ue8bV3QWud9JMw8Ai4dIdU/edit)

## 범위

- 기존 클라우드 연결을 유지하고 화면을 `CloudRoute`로 분리: 클라우드 홈/내 보관함/원본 백업/자동 백업/공간 확보 검토/구독 시험
- `CloudScreen`은 계정 상태와 작업 중 상태를 구분해 기존 Compose 화면을 선택적으로 표시
- 인증이 없는 경우 또는 계정 선택·로그아웃 중에는 이전 계정의 보관함·구독·백업 화면으로 들어가지 못하도록 제한
- UID 전환 시 화면 상태를 초기화하고, 세부 화면의 뒤로가기는 클라우드 홈으로 복귀
- 기존 `CloudAccountSettings`, `CloudBackupSection`, `CloudLibrarySection`, `CloudAutoBackupSection`, `CloudSpaceSection`, `CloudBillingSection` 기능을 재사용
- 라이트·다크, 기존 앱 스타일·스크롤·접근성 최소 터치 크기 기준 유지
- 공통 컴포넌트: `CloudHeader`, `CloudStatusMessage`, `CloudAccountCard`, `CloudUsageCard`, `CloudActionCard`, `CloudPrimaryAction`
- 구독 확인 실패와 구독 미보유를 혼동하지 않는 `CloudSubscriptionUiState` 기반 정의

## 적용하지 않는 변경

- 기존 기기 파일 정리·미디어 휴지통·Cloud 전송/삭제/인증 서버 계약 미변경
- 실서비스 구독 성공·실제 결제 상품의 가격·용량은 추정 표시하지 않음
- Google Play 스토어·Firebase Functions·AdMob 및 업로드/트랙 설정 미변경
- 로그인 소개·요금제/대시보드 상세 시안은 `CLOUD-UI-01~04` 후속 작업
- 폴더 생성/이름 변경/이동 및 실제 서버 계약은 `CLOUD-UI-05-B`에서 별도 작업

## 자동 검증

- `CloudUiFoundationTest`: 계정 작업 중 접근 차단, 서로 다른 계정의 페이지 격리, 구독 조회 실패와 미구독 구분, 만료 시 활성 처리 금지
- `CloudScreenNavigationTest`: 하위 화면 진입/복귀, UID 교체 시 홈 복귀, 로그아웃 시 클라우드 하위 화면 비표시
- 필수 GitHub Actions: Android verification + Android device tests + OPS-07
- CI 결과와 병합 HEAD가 확인되기 전에는 작업을 완료로 표시하지 않음

## 운영 차단 항목

Work 확인(2026-11-17):
- Play Console Data Safety는 `수집 또는 공유하지 않음`, App Access는 `제한된 부분 없음`으로 저장됨. 실제 Cloud 전송과 접근 제한을 반영하는 재신고 필요
- `gallipick-dev`에는 백업 함수가 배포되었으나 `verifyCloudSubscription`, `refreshCloudSubscription`은 배포 목록에서 누락됨
- 운영 항목은 [GP-CLOUD-QA #116](https://github.com/hebi10/GalliPick/issues/116)에서 추적하고, 기존 Play `0.2.13(24)`를 재배포하지 않음

# GalliPick 7-B — 홈·공통 시각 시스템

기준: 2026-11-06 디자인 정밀 비교 기획안, 원본 9화면 시안 및 7-A의 30개 MediaStore 기준 자료.

## 적용 범위
- H-01: 고양이 히어로의 메시지를 20sp 제목으로 강조하고, 주 CTA를 같은 정보 그룹 안에 배치. 기존 브랜드 Panel·공통 카드 표면·패딩으로 색상과 테두리를 재사용합니다.
- H-02: 저장공간은 기존 실제 totalStorageBytes / freeStorageBytes 값을 그대로 표시하고, 접근 가능한 미디어 건수와 별도 안내합니다. 예시 수치 하드코딩 금지.
- H-03: 네 개 빠른 실행의 기존 필터·중복 검사 경로를 유지하며, 좁은 화면에서는 목록형 76dp 이상 터치 카드로 전환하고 일반 폭에서는 이미지 중심 2열 카드를 유지합니다. PNG 일러스트를 tint로 변색시키지 않습니다.
- G-01~03: 기존 브랜드 캐릭터 API(GalliIllustration), 48dp 이상 터치 대상, 공통 카드 패딩과 버튼 규격을 우선 사용합니다.
- 320dp 또는 160% 큰 글씨에서는 히어로를 세로 적응형으로 구성해 본문/CTA가 이미지 폭에 압박받지 않도록 합니다.

## 기존 기능 보존
- 상위 탭은 홈·파일·정리·설정 4개; 중복 검사는 정리 하위 경로 유지.
- 용량 큰 파일은 100MB 이상, 오래된 파일은 촬영일 1년 이전 기준의 기존 필터 사용.
- 선택·보호·즐겨찾기·SHA-256 완전 중복 검사·Android 휴지통 이동/복원 승인 흐름에는 손대지 않습니다.
- 배포 설정, 앱 ID, 서명키, versionName/versionCode, Play 트랙은 수정하지 않습니다.

## 검증 계획
1. Android verification (빌드/단위 테스트/Lint), Android device tests 통과.
2. Adaptive UI verification and native captures: 5개 프로필 중 01-home의 7-A 기준선과 실제 UI 비교.
3. AdaptiveOverviewTest.compactHomeAtLargeFontKeepsHeroAndFourShortcutsOperable: 320dp·160% 글씨에서 헤드라인·CTA·바로가기 4개와 48dp 터치 영역 검증.
4. ReferenceHomeScreenTest: 빠른 실행 필터/중복 진입/저장공간의 실데이터 표시 회귀 검증.
5. 확인된 문제 수정·재검증 후에만 PR 병합. 실제 삼성 기기 TalkBack 수동 검수는 별도 미완료로 명시.

## 후속
7-C 파일·필터 → 7-D 중복 결과·비교 → 7-E 상세·미리보기·휴지통 → 7-F 설정·정리 결과 → 7-G 원본 재비교·최종 QA.

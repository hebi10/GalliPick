# GalliPick 8-A — 파일 갤러리 선택 원 좌상단 배치

기준: 2026-11-07 사용자 제공 1번 화면과 8단계 Drive 기획서. 소스 기준 main `e0239748930e2c90075b7b9133725ccc0a8f9b82`.

## 수정
- `SelectBox`의 기본 배치는 변경하지 않고 `indicatorAtTopStart`를 선택적으로 추가합니다. 공통 클릭 대상은 기존 48 × 48dp이며 시각적 24dp 원의 모서리 인셋만 12→4dp가 됩니다.
- `FilesScreen.kt`의 **그리드 타일만** 새 표시 위치로 변경하고, 파일 목록·중복·휴지통·필터의 체크박스는 중앙 정렬을 유지합니다.
- 체크박스 접근성 설명/선택 가능 여부/선택 시 이벤트는 유지하며, 이미지의 나머지 터치 영역은 파일 상세 화면을 여는 동작으로 유지합니다.
- 미디어 썸네일·텍스트·그리드 정렬·색상·파일 필터·정리 정책·앱 버전·Play 배포 설정은 변경하지 않습니다.

## 완료 조건
1. Android verification 성공: 빌드·단위 테스트·Lint.
2. Android device tests 성공: 48dp 클릭 영역·24dp 표시 원 4dp 인셋·클릭 선택과 파일 상세 별개 동작.
3. Adaptive UI verification and native captures 성공: 휴대폰 세로/가로/글자 160%, 태블릿 세로/가로 5개 환경.
4. 필수 캡처 `02-files`, `02b-files-list`에서 시각적 인셋·잘림·보호 아이콘 겹침 검사.
5. 최종 PR 커밋과 검사 성공 SHA가 일치할 때만 main 병합. 검증 실패 시 수정 후 재실행.
6. Google Play alpha v0.2.5/code16은 이번 작업에서 변경하거나 신규 제출하지 않습니다.

## 후속
8-B 날짜 필터 설명/기준 → 8-C 중복 목록 가로 스크롤 → 8-D 단일 화면 비교 → 8-E 결과 용량 정확도 → 8-F 오래된 파일 아이콘 → 8-G 최종 Android QA.


## 최종 검증 및 병합 — 2026-11-07
- PR #45: https://github.com/hebi10/GalliPick/pull/45 — main 병합 완료, merge SHA `ed40053efbd5a9b70720973e6db66a2fd57a3afe`.
- 검증 대상 최종 HEAD: `08c620b15ac07e407e8f3e7b32e77261f54a9bea`.
- Android verification: https://github.com/hebi10/GalliPick/actions/runs/42342143340 — success.
- Android device tests: https://github.com/hebi10/GalliPick/actions/runs/42342143240 — success.
- 5환경 실제 앱 캡처: https://github.com/hebi10/GalliPick/actions/runs/42342139440 — success.
- Google Drive 실제 Android 캡처 ZIP 5종: https://drive.google.com/drive/folders/1spI6MRC8W6RUY1J3ejt2d1wYHk4cvmOs
- 휴대폰 세로/가로 `02-files` 실제 앱 캡처 확인: 원의 표시 인셋 축소, 파일 썸네일·선택/보호 상태 정상. `qa/design-audit.json`이 모든 5프로필에서 통과함.
- 48dp 터치와 실제 표시 원 4dp 위치, 선택/상세 열기 분리는 기기 UI 회귀 테스트 성공으로 확인.
- Google Play v0.2.5(code16) 비공개 테스트는 이번 단계에서 재배포하지 않았습니다.

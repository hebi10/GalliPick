# GalliPick 8-G — 최종 Android 통합 QA 및 실캡처 대조

기준: 2026-11-07. 이전 8-A~8-F UI 개선 후 전체 검증 단계이며 Google Play 배포는 별도 결정입니다.

## 자동 검증
- GitHub Actions의 Android verification(컴파일/JVM/Lint) 및 Android device tests 통과.
- 5개 프로필: phone-portrait(1080x2400, 420dpi), phone-landscape(2400x1080, 420dpi), phone-large-text(1080x2400, 420dpi, 160%), tablet-portrait(1600x2560, 240dpi), tablet-landscape(2560x1600, 240dpi).
- 각 프로필에서 실제 MainActivity 15장 + 모달/상세 16장 = **31장**, 총 155장 생성 및 스크린샷 무결성 검증.
- 기존 `scripts/qa/verify_design_captures.py`의 7-G 기준을 보존하고 신규 `scripts/qa/verify_stage8_captures.py`가 핵심 4개 앱 화면(홈·파일·중복 목록·중복 비교)과 6개 모달(중복 비교·결과 5종)의 PNG 해상도, 내용 해시, 파일 누락 여부를 독립 기록합니다.
- 각 실행 artifact에 `qa/design-audit.json`과 `qa/stage8-audit.json`을 함께 포함합니다.

## 사람의 실제 캡처 검수
1. `01-home`: 오래된 파일 아이콘의 비율·정렬·배경 및 기존 세 아이콘과 톤 일치.
2. `02-files`: 선택 원이 썸네일 좌상단으로 보이되 48dp 터치 영역 유지.
3. `08-files-filter-dialog`: 날짜 기준 설명·오늘/직접 선택·160% 및 좁은 화면 버튼 접근성.
4. `09-duplicates-scanned`: keeper-first 묶음별 가로 스크롤·긴 파일명 생략·보호와 선택 상태·세로 스크롤.
5. `10-duplicate-comparison` 및 `06-duplicate-compare`: A/B 탭 제거, 전체 가로 스트립, 선택한 파일 상세, 명시적 '이 파일 남기기', 320dp·가로 안전 영역.
6. `12-cleanup-result`·`13-cleanup-partial`: 확인된 파일 수/용량, 요청 용량, '확보 공간 아님' 안내 및 하단 버튼이 서로 가리지 않는지 확인.
7. `14-16` 결과: 취소·실패·불명확 상태에 성공 메시지 노출되지 않음.

## 회귀 안전 정책
- 중복 판정 SHA-256 전체 일치, 남길 파일 1개 이상 유지, 500개 선택 한도.
- 보호·즐겨찾기 제외, keeper 변경 시 해당 묶음의 후보 선택만 초기화.
- 탭/미리보기 클릭만으로 휴지통 작업을 수행하지 않음.
- Android 시스템 승인 및 MediaStore 재조회, 실제 확인 완료 용량만 표시.
- 날짜 촬영일 `DATE_TAKEN` 우선, 없으면 `DATE_ADDED` 대체 설명 유지.
- 단위/에뮬레이터 검증 성공을 Samsung 실기기·Play Console 심사 또는 실결제 검증으로 표기하지 않음.

## 완료 조건
1. 같은 최종 HEAD에서 3개 GitHub 검증(verification/device/native 5프로필) 모두 성공.
2. 155장 캡처의 필수 주요 화면을 눈으로 검수하고 Android 시스템 영역·용량 표시·가로 스크롤 이상 여부 기록.
3. 성공한 최종 HEAD만 `main` 병합하며 자동으로 Google Play alpha에 새 버전을 제출하지 않음.
4. 5프로필 증거 ZIP을 기존 GalliPick Drive 기획 폴더 아래 8-G 전용 폴더에 보관.
5. Play v0.2.5(code16)는 유지하고, 다음 릴리스 v0.2.6(code17) 여부는 별도 배포 단계에서만 판단.

미수행 항목: 삼성 실제 휴지통 승인/복원·HEIC/다양한 MP4·1,000개 이상 미디어 성능, Google Play 테스터 실제 설치/심사 상태 확인(기존 QA 이슈 #40/#42).

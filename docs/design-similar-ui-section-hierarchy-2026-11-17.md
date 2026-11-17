# SIMILAR-UI-01~04 — 비슷한 사진 찾기 영역 구분

- 기준: 2026-11-17 GalliPick Mono Cat UI / `main` `85732a353882c0dbfab32c7caf8165b3ea80d536`
- 요청: 정리 > 비슷한 사진 찾기에서 분석 상태, 유사 사진 결과, 연속 촬영, 선명도 확인 및 정리 조작 영역의 경계와 정보 위계 개선
- 범위: `PhotoAnalysisScreen.kt`, `SimilarPhotoResultsScreen.kt`, `SimilarPhotoCompareScreen.kt` 및 해당 Android 계측·캡처 테스트
- 제외: pHash·연속 촬영·베스트 컷 점수, MediaStore, 보호·즐겨찾기·휴지통 승인, Cloud, Google Play, Firebase, 서명·버전

## 공통 디자인 규칙

- 기존 `AppTheme.kt`의 Light/Dark `AppBackground`, `AppSurface`, `Panel`, `Line`, `Ink`, `Muted`, `Primary`만 사용
- `SimilarSectionCard`: 전체 1dp Line 테두리, 기존 `GalliUiMetrics.cardCorner`와 `cardPadding`, 내부 세로 간격 8dp. 별도 그림자·그라데이션·동작 효과 추가 없음
- 요약 상태는 Panel 배경, 주요 기능은 AppSurface 카드. 섹션 제목은 titleMedium, 카드 제목은 titleSmall, 설명은 bodySmall
- 화면은 기존 단일 세로 스크롤을 유지. 각 그룹의 사진 목록 가로 이동은 기존 LazyRow 그대로 보존
- 사진·묶음의 카운트/용량/추천 상태는 소스 데이터에서만 표시하며 예시값을 넣지 않음

## SIMILAR-UI-01 — 분석 화면

1. 헤더를 실제 메뉴명인 `비슷한 사진 찾기`로 통일하고 목적 안내를 한 줄로 표시
2. 분석 미시작/진행/일시정지/완료/실패 및 접근·목록 오류는 별도 상태 카드에서 처리
3. 완료된 분석이 유효할 때만 결과 영역 표시
4. 가장 중요한 유사 사진 결과를 별도 카드로 강조하고 `유사 사진 결과 보기` 유지
5. `추가 분석 결과` 아래 연속 촬영과 선명도 카드를 각각 분리
6. 서버 미전송·자동 삭제 없음·부분 권한 설명은 하단 보조 안내로 이동

## SIMILAR-UI-02 — 유사 사진 결과 목록

1. 요약 카드(묶음 수/사진 수/설명)와 묶음 목록을 분리
2. 각 묶음 카드에 번호, 촬영 시각(해당 유형에 한정), 장수, 용량, 현재 선택한 정리 후보 수를 표시
3. 추천 사진/판단 보류 근거를 중성 보조 배경으로 시각적으로 구분. 추천만으로 정리 후보를 자동 지정하지 않음
4. 기존 사진 썸네일 가로 목록과 카드 전체의 비교 진입 동작 유지
5. 각 카드 하단의 `사진 비교` 영역을 시각적으로 분리
6. 선택된 사진의 휴지통 이동 검토 버튼은 기존대로 하단에만 노출

## SIMILAR-UI-03 — 비교 상세

1. 묶음의 위치·남길 사진·정리 후보 집계를 요약 카드로 표시
2. 추천/판단 보류와 이유 펼치기 내용을 별도 카드로 분리
3. 비교 사진 가로 목록을 `비교할 사진 선택` 카드로 그룹화
4. A/B 모드는 기존 슬라이더·확대·A/B 대상 선택·수동 정리 후보 동작을 유지하고 미리보기 카드에 표시
5. 한 장 보기 모드는 미리보기, 선택한 사진 정보, 정리 상태(선택/취소/보호 정책)를 서로 다른 카드로 구분
6. 하단 최종 Android 승인 안내와 정리 버튼은 기존 기능 및 노출 조건을 유지

## SIMILAR-UI-04 — QA

- [ ] Android verification: JVM, Lint, Debug APK 빌드 성공
- [ ] Android device tests: IDLE/RUNNING/PAUSED/COMPLETE·미디어 권한 변화, 결과 묶음 카드 탐색, 추천 펼침, 사진 미리보기와 후보 선택 분리, 마지막 1장 보존, 보호·즐겨찾기
- [ ] 기존 A/B 동기 확대·슬라이더·화면 복귀·선택 상태 보존
- [ ] 세부 비교의 세로 스크롤 1개, 결과 썸네일 가로 스크롤 유지
- [ ] 5개 환경(휴대폰 세로/가로/글꼴 160%, 태블릿 세로/가로) × 라이트/다크 최신 Android native 캡처 시각 점검
- [ ] OPS-07 Play 변경 차단 정책 검사 성공, 최신 PR HEAD 검증 후 안전 병합
- [ ] 삼성/폴더블 실기기 원본 파일 삭제 안전성 최종 검증은 별도. 에뮬레이터 QA로 대체하지 않음

## 관련 근거

- 기획/디자인: `docs/design-stage9b-photo-analysis-2026-11-08.md`, `docs/design-stage9c-similar-photo-review-2026-11-08.md`, `docs/design-stage12b-best-cut-recommendation-2026-11-09.md`
- 기존 A/B QA: https://docs.google.com/document/d/18nEslZSMECrvZyyIX5MythMEYaxcyjDhq19WxZwU7ow/edit
- 현재 파일: `app/src/main/java/com/hebi/gallipick/ui/PhotoAnalysisScreen.kt` 등
- 검증: `PhotoAnalysisScreenTest`, `SimilarPhotoReviewScreenTest`, `Stage9GFeatureCapturesTest`

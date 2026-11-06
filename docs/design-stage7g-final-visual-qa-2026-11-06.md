# GalliPick 7-G — 원본 9화면 전체 대조·최종 Android QA

기준: 디자인 원본 9화면(2026-11-05) 및 Drive '7단계 원본시안 vs 실제캡처' 기획서. 소스 기준 7-F PR #37 후속 코드(Play 비공개 테스트 v0.2.4/code15 유지).

## 최종 9화면 및 실증 근거
| ID | 원본 화면 | 검증용 실제 Android 캡처 |
| --- | --- | --- |
| G-01 | 홈 | native 01-home |
| G-02 | 파일 목록 | native 02-files, 02b-files-list |
| G-03 | 필터 설정 | native 08-files-filter-dialog + 키보드 08b |
| G-04 | 중복 검사 결과 | native 09-duplicates-scanned(중복 묶음 3개 이상) |
| G-05 | 중복 A/B 비교 | native 10-duplicate-comparison + adaptive 비교 캡처 |
| G-06 | 파일 상세 | native 02c-real-jpeg-detail, modal 03-file-detail |
| G-07 | 휴지통 | **실제 MainActivity**의 05c-trash-empty-real-activity + 시험용 실제 사진 11개가 채워진 modal 09-trash-list |
| G-08 | 설정 | native 05-settings-dark 및 05b-settings-light |
| G-09 | 정리 결과 | modal 12-cleanup-result + 13-partial / 14-canceled / 15-failed / 16-unknown |

## 7-G 구현
- NativeScreenCaptureTest는 기존 14개 화면에 05c real-activity 휴지통 빈 상태를 추가, 전체 15화면을 캡처. 기존 모달 테스트는 시스템 statusBarsPadding이 없는 단독 Surface이므로 실제 앱의 시스템 영역 검수로 오해하지 않음
- 표준 라이브러리 Python 검사 scripts/qa/verify_design_captures.py는 9개 원본 대응 화면과 결과 5종, 고유 PNG 파일·해상도·SHA-256, 사진 24/영상 6/보호 2 예시 데이터, 누락/중복 및 결과 상태 차이를 검증
- 각 프로필의 qa/design-audit.json을 GitHub Artifacts와 Google Drive에 보관
- 전체 Android verification + device tests + Adaptive UI captures(휴대폰 세로/가로/160% 큰 글씨, 태블릿 세로/가로)를 통과한 뒤 PR 병합
- 새 버전이나 Google Play 배포는 이번 순수 디자인·QA 단계에 포함되지 않음

## 평가 기준
- 원본은 시각적 방향과 정보 위계를 나타내는 시안이며, 실제 앱의 사용권한·동적 값·Android 플랫폼 기본 조작은 1:1 픽셀 복제보다 정확성이 우선됨
- 품질 체크: 시스템 상태바/탭과 콘텐츠 충돌 없음, 사진/영상 미리보기·썸네일 실제 표시, 48dp 이상 조작, 큰 글씨에서 스크롤/동작 접근, 대비 및 라이트·다크 구분, 성공/불확실/취소 안내 혼동 없음
- 최소 한 번 5개 프로필 × 15 native 및 16 modal = 155장의 기본 QA 캡처를 생성(기존 adaptive extra screenshots는 별도)
- 심한 클리핑·겹침·오동작·안전 정책 훼손 없음을 확인해야 단계 완료

## 수동 실기기 QA로 남는 항목
- 삼성 휴대폰/폴더블 실제 11~16+ Android 운영체제에서 사용권한·시스템 확인창·휴지통 복원·TalkBack
- 다양한 촬영 원본 HEIC, 여러 코덱/길이 MP4, 외장 SD, 1000개 이상 미디어/장시간 SHA-256 검사
- Play Console 심사/테스터 설치 가능 여부는 Google Play 운영 검증으로 별도 판단

## 최종 검증 및 병합 상태
CI 결과/드라이브 스크린샷 대조 완료 후 이 절을 업데이트합니다.

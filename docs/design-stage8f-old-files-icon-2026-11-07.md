# GalliPick 8-F — 오래된 파일 홈 아이콘 통일

2026-11-07, `gp_old_files.png` 신규 256×256 PNG(투명 여백, 크림/민트/진녹색).

## 변경 사항
- 홈의 세 바로가기 `gp_photo`, `gp_duplicates`, `gp_video`와 동일하게 `imageRes` 이미지 렌더 경로로 통일합니다.
- '오래된 파일'의 `Icons.Rounded.DateRange`를 신규 `R.drawable.gp_old_files`로 교체합니다.
- 바로가기 카드 높이, 아이콘 표시 크기(56dp/48dp), 클릭 이벤트·1년 이전 날짜 조건은 변경하지 않습니다.
- 기본/큰 글씨/가로/태블릿 실제 01-home Android 캡처와 기존 3개 에셋을 대조합니다.
- Play 버전, 서명 및 테스트 트랙은 변경하지 않습니다.

## 병합 필수
Android verification + Android device tests + 5개 환경 native captures 성공 후 실제 01-home 화면에서 아이콘 크기·정렬·자르기 확인. 단계 선행 관계 8-D/8-E 병합 확인 후 main 병합.

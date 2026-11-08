# GalliPick 한국어 Google Play 이미지 · v0.2.7 (2026-11-08)

이번 등록용 이미지 세트는 Google Play 휴대전화 설명 이미지 **8장**(1350 × 2400, RGB PNG)과 대표 그래픽 **1장**(1024 × 500, RGB PNG)입니다.

- 앱 ID: `com.hebi.gallipick`, 등록정보 언어: `ko-KR`
- 실제 Android 캡처 소스: `4729c85428c6ef7c82f6ee3ebceb69b51d5d5fc5` · [PR #57 5개 기기 환경 검증](https://github.com/hebi10/GalliPick/actions/runs/42430836251)
- 현재 배포 코드: `5f9937ada6f17c1579ea107f56fd060c8af2c61f` · v0.2.7/code 18
- 예제 사진·영상은 에뮬레이터 검수용 테스트 미디어입니다. 개인 사용자 갤러리를 사용하지 않았습니다.
- 앱 안쪽 UI는 실제 Android 비트맵 그대로 유지했으며, 외부 여백에 제목과 브랜드 색상을 합성했습니다.
- 파일별 원본 및 SHA-256 증거: [manifest.json](ko-KR/manifest.json)

## 업로드 순서

1. `01-home.png` — 사진·영상 정리 홈
2. `02-files.png` — 파일 목록과 검색
3. `03-duplicates.png` — 완전히 같은 파일 검사
4. `04-comparison.png` — 남길 사본 비교
5. `05-cleanup.png` — 종류별 정리
6. `06-date-filter.png` — 촬영·추가·수정 날짜 필터
7. `07-large-files.png` — 파일 용량순 정렬
8. `08-trash.png` — Android 휴지통 이동 전 확인

**정확성 안내:** 05는 검사 예제에서 정리 후보가 없는 상태를 보여 줍니다. 06은 날짜 필터 화면이지 다운로드 날짜별 결과가 아닙니다. 07은 전체 파일 용량순 정렬 화면이지 10MB 이상 사진만 추린 화면이 아닙니다. 제공하지 않는 유사 사진 감지, 자동 삭제 또는 즉시 저장공간 확보를 암시하지 않습니다.

## 배포 범위 및 검증

[스토어 이미지 전용 워크플로](../../.github/workflows/play-listing-images.yml)는 이미지와 manifest의 개수·순서·RGB·크기·SHA-256을 검사한 뒤 한국어 `phoneScreenshots`와 `featureGraphic`만 한 번의 Google Play edit로 교체합니다. 이미 존재하는 앱 릴리스 AAB, 서명키, 비공개 테스트·내부 테스트·프로덕션 트랙, 다른 국가/언어의 등록정보, 스토어 아이콘은 수정하지 않습니다.

Google Play API의 edit commit 및 별도 readback 성공과 스토어 심사 승인·공개 노출은 별개입니다.

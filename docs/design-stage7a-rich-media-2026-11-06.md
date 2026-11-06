# GalliPick 7-A — 30개 실제 미디어 기반 기준 캡처

## 목적
- 6단계 기준 캡처의 사진 3개·영상 1개 한계를 제거하고, 갤러리 밀도·중복 묶음·파일 상세 미리보기의 실동작을 검증합니다.
- 이 문서는 기능/UI 변경 요구가 아닌 계측 테스트 fixture/증거 기준입니다. 7-B~7-G의 화면 개선 기준으로 사용합니다.

## 데이터셋(개인 사진 미사용)
- 24개 사진: PNG 리소스 3개(고양이 원본/동일 사본·아이콘) + 서로 다른 피사체·가로/세로 비율을 가진 JPEG 장면 21개. JPEG는 테스트 코드가 생성한 재현 가능한 합성 풍경이며 실존 장소 사진이 아닙니다.
- 6개 영상: Android instrument assets의 기존 재생 가능한 MP4를 이름이 다른 6개 MediaStore 레코드로 복제. 하나의 동일한 영상 묶음이므로 장면·길이가 모두 다양한 영상이라는 의미는 아닙니다.
- SHA-256 중복 묶음: PNG 고양이 1개 + JPEG 풍경 3개 + MP4 1개로 최소 3개를 초과하는 5개 묶음 예상. 검사 결과는 실제 앱의 해시 결과를 기준으로 확인하며, 테스트에서 세 묶음 이상을 요구합니다.
- 보호 미디어 2개: EXAMPLE_scene_00.jpg, EXAMPLE_scene_10.jpg. 보호 데이터는 계측 테스트 중에만 보관하고 종료 시 해제합니다.
- 스크린샷 폴더·촬영 시각이 1년 이상 지난 자료: EXAMPLE_Screenshot_04/05.jpg; Trips/ 하위 폴더도 포함합니다.
- 모든 샘플은 Android MediaStore에 실제 게시되며 사진 디코딩과 영상 메타데이터 읽기를 검사합니다.
- 생성·삭제는 계측 실행 범위에서만 수행하며 개인 기기/운영 앱에는 fixture를 설치하지 않습니다.

## 비교 기준
- 공통 4탭(홈·파일·정리·설정), 갤러리 그리드/목록, 실제 JPEG 파일 상세, 필터/키보드, 3개 이상 중복 묶음, 다크·라이트 설정, 비교 화면. 기존 11장에 02b 목록·02c JPEG 상세·05b 라이트 설정을 추가한 14개의 네이티브 캡처를 확보합니다.
- 별도 ModalSurfaceCaptureTest의 12개 모달/상세 화면은 더 이상 file:// 캐시 샘플을 미디어 사진으로 가장하지 않고 content:// MediaStore URI를 통해 실제 JPEG·MP4를 표시합니다. 자리표시 사진인지 자동 검증 후 캡처합니다.
- 프로필 5개: phone-portrait(1080×2400), phone-landscape(2400×1080), phone-large-text(160%), tablet-portrait(1600×2560), tablet-landscape(2560×1600).
- 검증: Android verification, Android device tests, Adaptive UI verification and native captures 5개 프로필 모두 성공; JSON manifest의 photos=24, videos=6, protectedMedia=2, captures=14.
- 0개·4개 데이터 기준은 6단계의 EmptyState/권한 UI 테스트 및 이전 4개 기준 캡처를 별도 비교 자료로 유지합니다. 30개 fixture를 1,000개 스트레스 검증이나 영상 길이 다양성 검증으로 대체하지 않습니다.

## 유의
- Android 기기 테스트 fixture 생성은 검증용 코드인 app/src/androidTest 아래에만 포함합니다.
- Play 비공개 테스트·버전 코드·서명키·배포 트랙·운영 앱 UI·삭제 정책은 수정하지 않습니다.
- 7-A 통과 후 7-B부터 디자인 자체를 보정합니다. 실제 상세 이미지가 정상 표시되면 기존의 '미리보기 없음' 판정은 테스트 sample 문제로 재분류하고, 실제 장애 재현은 별도 검증합니다.

## 기준 링크
- [원본 디자인](https://drive.google.com/file/d/1147DiXv6uZoHm86eRV65n-bNA0yGrz4O/view)
- [7단계 개선 기획안](https://docs.google.com/document/d/1Pct7aB9O0NP3Zh621lcEz0cOvuf4JNML/edit)
- [6단계 캡처 자료](https://drive.google.com/drive/folders/1I_Dh4apaZC4xyrlA7uum6lt2y0YIu65C)

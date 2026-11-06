# GalliPick Android 테스트 미디어

이 경로의 파일은 `androidTest`에서만 사용하며 실제 Play 릴리스의 기본 사용자 미디어에 추가되지 않습니다.

- `preview-baseline.mp4`: 기존 디코딩·재생 테스트의 H.264 기준 영상. 변경하지 않습니다.
- `store-coast-preview.mp4`: GalliPick 스토어 스크린샷용으로 생성한 자연 해안 풍경 샘플. 4.0초 / 1280 × 720 / H.264 Constrained Baseline / yuv420p / 24fps / 무음.
- Coast 영상 SHA-256: `b8bc2064de65ee4fc1664c49e79a5b39e4443f01e51ed15b72e0576f9dcf85ae`.
- 해안 풍경 기반 이미지는 GalliPick 프로젝트를 위해 생성됐고 제3자 스톡 사진을 복제하지 않습니다. 기존 PR #6의 파일 blob을 **변경 없이** 현재 테스트 구조로 가져왔습니다.
- `Stage7aFixtureMedia.seed`는 6개 시험용 MP4 중 3개는 기존 기준 영상, 3개는 새로운 해안 영상으로 구성합니다. 서로 다른 두 파일의 디코딩·메타데이터를 검사하면서 동일 바이트 중복 사본 테스트도 유지합니다.
- 같은 해상도/재생시간이라 실제 다양한 코덱·프레임레이트·길이의 영상 테스트를 대체하지 않습니다. 실제 실기기 QA는 이슈 #40에서 별도 관리합니다.

테스트 실행은 `NativeScreenCaptureTest`에서 실제 Android MediaStore에 파일을 등록한 뒤 수행하며, 종료 시 앱이 소유한 시험 파일을 제거합니다.

# GalliPick

사진·동영상을 조건으로 찾고 완전히 같은 사본을 안전하게 정리하는 Android 앱입니다.

## 현재 구현

- 사진·영상, 파일명/경로 검색, 폴더, MIME 형식, 용량 범위, 영상 길이, 촬영/저장/수정 날짜, 화소 수, 방향, 즐겨찾기/보호 여부 필터.
- 날짜·크기·길이·이름·화소 수 정렬과 조건 저장/적용/삭제, 마지막 조건 유지.
- 크기가 같은 후보의 원본 전체 바이트 SHA-256 검사. 불완전 해시와 읽기 실패 파일은 중복으로 표시하지 않습니다.
- 즐겨찾기·보호 파일을 제외한 사본 추천, 남길 사본과 휴지통 대상의 최종 상태 확인.
- 파일·폴더 보호, 다중 선택, 원본을 재생/확대할 앱 열기, 메타데이터 보기.
- 시스템 승인 휴지통 이동·복원. 한 번에 최대 500개. 영구 삭제 기능은 없습니다.
- 실제 기기 저장 공간, 접근 가능한 미디어 통계, 큰 영상·오래된 스크린샷·화면 녹화 후보.
- 일시정지/재개 가능한 WorkManager 검사와 완료 파일 해시 캐시.
- 작은 화면 하단 탭, 넓은 화면 탐색 메뉴와 파일/상세 좌우 배치.

## 실행

Android 11 이상이 필요합니다. Java 17과 Android SDK 36으로 빌드합니다.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows PowerShell에서는 다음을 사용합니다.

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

테스트 APK 경로는 `app/build/outputs/apk/debug/app-debug.apk`입니다. Android Studio에서 저장소를 열어 기기로 실행할 수도 있습니다. GitHub Actions는 단위 검사·lint·APK 빌드와 별도의 에뮬레이터 통합 검사를 수행합니다. Actions APK는 개발용 debug 빌드입니다.

## 처음 사용

1. 홈에서 사진·동영상 접근을 허용합니다. 일부 파일 또는 한 종류만 허용하면 허용 범위만 표시됩니다.
2. 파일 탭에서 필터/정렬을 설정합니다. 자주 쓰는 조건은 이름을 붙여 저장합니다.
3. 중복 탭에서 검사를 시작합니다. 추천 선택 후 파일을 확인하고 휴지통 이동을 요청합니다.
4. 설정의 휴지통에서 복원을 요청할 수 있습니다. 보관 기간과 실제 파일 제거는 Android가 관리합니다.

## 개인정보와 안전

인터넷 권한, 서버, 계정, 광고·분석 SDK를 사용하지 않습니다. 자동 백업도 꺼져 있습니다. 분석·보호 설정·필터는 기기에 저장됩니다.

Android가 위치 메타데이터를 가린 파일을 원본으로 오인하지 않도록 정확한 중복 검사에는 `ACCESS_MEDIA_LOCATION`을 요청합니다. 이는 파일에 포함된 메타데이터 접근이며 실시간 GPS 추적을 사용하지 않습니다. 원본 읽기가 허용되지 않은 파일은 검사에서 건너뜁니다. 권한 거부 후에도 허용된 미디어의 정렬·검색을 사용할 수 있습니다.

휴지통 요청 직전에 URI·크기·수정일·generation, 즐겨찾기·보호 상태를 확인합니다. 확인된 중복 묶음은 선택하지 않은 사본의 존재도 확인합니다. 휴지통으로 이동한 용량이 즉시 여유 공간으로 바뀌지는 않을 수 있습니다.

## 현재 제한과 다음 단계

- **유사 사진/pHash 및 유사 동영상 분석은 아직 구현하지 않았습니다.** 현재 중복은 완전 일치만 의미합니다.
- 클라우드에만 있는 파일, 다른 앱의 비공개 저장소는 조회 대상이 아닙니다.
- 미디어 카탈로그는 앱 복귀·새로고침·검사 시 다시 조회합니다. generation 기반 전체 증분 동기화와 목록 Paging은 후속 단계입니다.
- 수만 파일과 실제 폴더블 성능은 실기기 검증이 필요합니다.
- 작업은 약 2분 단위로 나누되, 파일 하나의 해시는 끝까지 읽습니다. Android 실행 제한으로 중단된 파일은 처음부터 다시 읽으며 반복 중단 3회 시 일시정지합니다. 매우 큰 단일 파일은 현재 버전에서 완료가 어려울 수 있습니다.
- 스크린샷·화면 녹화 추천은 폴더/파일명 기준 후보입니다. 내용 인식이나 불필요한 파일 확정이 아닙니다.
- 보관 추천은 보호·즐겨찾기·Camera 폴더·날짜 기준입니다. 화질/압축 손상이나 원본·편집본을 AI로 판정하지 않습니다.

설계와 작업 계획은 `docs/superpowers/`에, 실제 검사 결과는 `docs/verification.md`에 기록합니다.

## 플랫폼 참고

- [MediaStore와 시스템 휴지통](https://developer.android.com/training/data-storage/shared/media)
- [Android 14 선택된 사진 접근](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)
- [장시간 WorkManager 작업의 실행 제한](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)
- [Android Gradle Plugin 8.13 호환성](https://developer.android.com/build/releases/agp-8-13-0-release-notes)

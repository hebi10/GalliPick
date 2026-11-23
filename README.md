# GalliPick

GalliPick은 사진과 동영상을 검색하고, 중복 파일과 유사 사진을 검토해 정리하는 Android 앱입니다.

현재 소스 버전은 **v0.2.26** (`versionCode 37`)이며, 패키지는 `com.hebi.gallipick`입니다. Google Play의 트랙별 버전, 심사 및 테스터 제공 상태는 Play Console에서 확인합니다. [버전 설정](app/build.gradle.kts) · [릴리스 노트](docs/play-store/release-notes-ko.txt)

## 주요 기능

- **파일 탐색** — 파일명·폴더로 검색하고 미디어 종류, 날짜, 크기, 영상 길이 등으로 필터링합니다. 사진을 확대하거나 동영상을 재생하고, 자주 쓰는 조건을 저장할 수 있습니다.
- **완전히 같은 파일 찾기** — 크기가 같은 후보를 원본 전체의 SHA-256 해시로 비교합니다. 남길 파일과 휴지통 대상을 확인하고, 보호 파일과 즐겨찾기를 정리 대상에서 제외합니다.
- **유사 사진 비교** — 사진의 시각적 특징을 pHash로 계산해 비슷한 사진을 묶습니다. 연속 촬영 묶음과 대표 컷 추천을 제공하며, 정리 대상은 사용자가 사진을 비교한 뒤 선택합니다. [유사 사진 검토 설계](docs/design-stage9c-similar-photo-review-2026-11-08.md)
- **사진 속 글자 검색** — 사용자가 기기에서 한국어·영문 OCR 색인을 시작합니다. 앱이 접근할 수 있는 색인된 사진에서 글자를 검색하며, 설정에서 색인을 초기화할 수 있습니다. [OCR 검색 설계](docs/design-stage13a-local-ocr-search-2026-11-09.md)
- **휴지통과 복원** — 이동할 파일과 남길 중복 사본을 확인한 뒤 Android에 승인을 요청합니다. 앱의 휴지통에서 복원을 요청할 수 있으며, 기기 휴지통의 보관과 최종 제거는 Android가 관리합니다. 파일은 한 번에 최대 500개까지 선택할 수 있습니다.

## 빌드 구성

| 구성 | 클라우드 | 광고 |
| --- | --- | --- |
| 기본 `release` · `playPreparation` | `localOnly` 연결을 사용하며 Firebase 계정 연결 없이 기기 내 정리 기능을 제공합니다. | 기본값은 광고 요청 비활성화입니다. `noAds` 매니페스트가 광고 ID 관련 권한 두 가지를 제거합니다. |
| Play Cloud `release` | `gallipickPlayCloudPilot=true`와 해당 환경의 Firebase 설정이 필요합니다. 개발 환경이 기본이며, 운영 환경은 `gallipickCloudEnvironment=production`으로 명시합니다. | 광고를 활성화하지 않은 경우 홈의 미리보기 배너를 앱에서 표시할 수 있습니다. |
| 광고 활성화 `release` | Play Cloud와 별도로 빌드에서 설정합니다. | `gallipickAdsEnabled=true`와 GalliPick AdMob 앱·배너 ID가 필요합니다. 동의 상태와 서버에서 확인한 광고 제거 권한에 따라 홈 배너를 요청합니다. |

`localOnly`와 `noAds`는 소스 및 매니페스트 구성입니다. 공통 매니페스트에는 인터넷 권한이 선언되어 있고, Google Mobile Ads와 Play Billing 의존성은 공통으로 포함됩니다. 일반 `debug`는 개발용 클라우드 소스를 포함하며 광고 요청은 기본으로 꺼져 있습니다. [Gradle 설정](app/build.gradle.kts) · [공통 매니페스트](app/src/main/AndroidManifest.xml) · [광고 운영 안내](docs/ads-01-admob-play-console.md)

## 클라우드와 시크릿 보관함

Play Cloud 구성에는 Google 로그인, Play 구독의 서버 검증, 사진·동영상 원본 백업·조회·복원을 위한 코드가 포함됩니다. 서버가 검증한 활성 클라우드 구독에는 광고 제거 혜택이 포함되며, 광고 제거 전용 구독의 백업 할당량은 0입니다. 운영 앱에서 각 기능을 이용할 수 있는지는 Firebase 설정, 서버 배포 및 Play 상품 상태에 따라 달라집니다.

시크릿 보관함은 클라우드 탭에서 별도 비밀번호로 여는 공간입니다. 서버가 발급한 만료 시간이 있는 세션으로 원본에 접근합니다. 기기에서 수행하는 파일 정리, 클라우드 백업, 클라우드 데이터 삭제와 계정 탈퇴에는 각각 별도의 흐름이 있습니다.

| 확인 항목 | 문서 |
| --- | --- |
| 운영 설정과 백업·복원 검증 | [운영 클라우드 인수인계](docs/production-cloud-handoff-2026-11-18.md) |
| 구독·업로드 운영 상태 | [읽기 전용 사전 점검](docs/gp-checkout-ops-02-readonly-preflight-2026-11-20.md) |
| 보존·관리자 운영 정책 | [클라우드 수명주기 계획](docs/admin-cloud-lifecycle-plan-2026-11-20.md) |
| 클라우드 데이터 삭제·회원 탈퇴 | [서버 구현 및 검증 범위](docs/gp-exit-03-05-server-implementation-2026-11-20.md) |

## 실행과 검증

Android 11 이상에서 실행됩니다. 빌드에는 Java 17과 Android SDK 36이 필요합니다. 릴리스에 사용하는 인증서는 [고정 서명 설정](docs/signing.md)에서 확인하세요.

일회용 테스트 서명으로 디버그 검증을 실행할 수 있습니다.

```bash
./gradlew -PgallipickTestSigning=true testDebugUnitTest lintDebug assembleDebug
```

```powershell
.\gradlew.bat -PgallipickTestSigning=true testDebugUnitTest lintDebug assembleDebug
```

PR 기본 CI는 [Android verification](.github/workflows/android.yml)에서 확인합니다. 기능 변경을 모두 반영한 동결 `main`의 [Android 기기 테스트](.github/workflows/device-tests.yml)와 그 결과를 [검증 기록](docs/verification.md)에서 확인할 수 있습니다.

## Google Play 수동 배포

1. 릴리스에 포함할 작업과 QA를 마치고, 동결한 `main`의 정확한 커밋으로 기본 CI와 전체 Android 기기 테스트를 통과합니다.
2. 승인 후 [서명 AAB 빌드](.github/workflows/release-build.yml)에서 AAB, SHA-256, 빌드 매니페스트를 생성합니다.
3. 내부 테스트 요청을 받으면 [검증된 AAB의 내부 테스트 제출](.github/workflows/play-internal-release.yml)을 실행합니다. 내부 테스트를 명시적으로 요청한 경우 [1회 승인 워크플로](.github/workflows/internal-release-once.yml)로 CI 확인, 최종 기기 QA, 서명, 내부 제출을 이어서 실행할 수 있습니다.
4. 비공개 테스트를 별도로 승인받으면 내부 테스트가 완료된 동일 `versionCode`를 [비공개 테스트로 승격](.github/workflows/play-closed-release.yml)합니다.

자동 CI는 검사와 디버그 빌드를 실행합니다. 공개 테스트, 정식 출시, 스토어 등록정보 변경, Firebase 배포는 각각 승인된 범위에서 진행합니다. Play API 접수, Console 게시, 테스터 설치 가능 여부는 따로 확인합니다. [저장소 배포 정책](AGENTS.md)

## 개인정보와 기기 접근

- Android에서 허용한 사진·동영상만 표시합니다. 정확한 중복 검사에는 원본의 위치 메타데이터에 접근하기 위한 `ACCESS_MEDIA_LOCATION`이 필요하며, 실시간 위치 추적은 사용하지 않습니다.
- 기기 내 분석·보호 설정·필터·OCR 색인은 로컬에 저장되며, Android 자동 백업은 꺼져 있습니다. 클라우드 원본은 Play Cloud 기능으로 별도 관리합니다.
- 파일을 휴지통으로 이동하기 직전에 파일 버전과 보호 상태를 다시 확인합니다. 최종 결과를 조회해 완료·변경 없음·확인 불가를 구분합니다. [개인정보처리방침](docs/privacy.html)

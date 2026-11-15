# CLOUD-01 · Google 로그인 테스트

GalliPick Dev는 `com.hebi.gallipick` 계정 연결을 검증하는 Firebase 프로젝트다. 디버그 빌드의 **클라우드** 탭에서 Google 계정을 연결, 전환하거나 로그아웃할 수 있다. Firebase 설정이 없는 디버그 빌드에서도 기존 로컬 사진·영상 기능을 이용할 수 있다. 일반 출시와 Play 준비 빌드는 기본적으로 로컬 기능을 제공한다.

## 테스트 빌드 설정

1. [Firebase 프로젝트 일반 설정](https://console.firebase.google.com/project/gallipick-dev/settings/general)에서 `com.hebi.gallipick` 앱을 확인한다.
2. 아래 명령으로 실제 빌드에 사용할 디버그 인증서의 SHA-1과 SHA-256을 확인한다. 이 인증서가 Firebase에 등록돼 있는지 확인한다. 기존 Play 설치본과 같은 기기에서 실험할 때는 별도 테스트 프로필이나 기기를 사용한다.

   ```powershell
   .\gradlew.bat -PgallipickTestSigning=true :app:signingReport
   ```

3. [Authentication 로그인 방법](https://console.firebase.google.com/project/gallipick-dev/authentication/providers)에서 Google을 확인한다. Firebase에서 다운로드한 최신 `google-services.json`을 `app/src/debug/google-services.json`에 저장한다. 이 파일은 Git 추적 대상에서 제외된다.
4. 아래 명령으로 테스트 서명 APK를 빌드한다. Google Play 서비스가 있는 테스트 기기에서 **클라우드** 탭을 연다.

   ```powershell
   .\gradlew.bat -PgallipickTestSigning=true :app:assembleDebug
   ```

## Play 비공개 테스트 빌드 설정

1. Play Console의 앱 무결성에서 `com.hebi.gallipick`의 **앱 서명 키 인증서 SHA-1**을 확인한다. 이 인증서를 GalliPick Dev의 Android 앱에 등록하고 Google 제공자가 활성화된 상태에서 새 `google-services.json`을 내려받는다. 기존 업로드 키와 고정 서명 검증은 AAB 생성에 사용한다.
2. Firebase 설정 파일을 `app/src/release/google-services.json`에 둔다. Git 추적에서 제외되는 경로다. 빌드 환경의 `ORG_GRADLE_PROJECT_gallipickPlayAppSigningSha1`에 확인한 앱 서명 키의 SHA-1을 설정한다.
3. 비공개 테스트용 서명 AAB를 만들 때 다음 Gradle 옵션을 사용한다.

   ```powershell
   .\gradlew.bat -PgallipickPlayCloudPilot=true :app:bundleRelease
   ```

옵션이 켜진 `release`는 디버그 빌드와 같은 클라우드 연결을 사용한다. 설정 파일, Firebase 프로젝트, 앱 패키지, Google OAuth 항목, Play 앱 서명 SHA-1이 일치해야 빌드가 진행된다. `playPreparation`은 로컬 기능만 포함한다. 실제 비공개 테스트에서는 Play에서 설치한 빌드로 Google 로그인과 허용된 테스트 계정의 백업·복원을 확인한다. [Play 앱 서명 인증서 안내](https://developers.google.com/android/guides/client-auth?hl=ko)

GitHub Actions에서는 Firebase 설정 파일의 Base64 값을 `GALLIPICK_PLAY_CLOUD_CONFIG_BASE64` secret에 보관하고 Play 앱 서명 키 SHA-1을 `GALLIPICK_PLAY_APP_SIGNING_SHA1` variable에 설정한다. 검증된 `main`에서 `OPS-03 Signed AAB build request`의 `cloud_pilot` 입력을 선택하면 서명 빌드가 `-cloud-pilot-play-bundle` 아티팩트를 생성한다. 내부 테스트에서 같은 AAB를 검증한 뒤 그 `versionCode`를 기존 `alpha` 트랙으로 승격한다.

## 실제 계정 검증

| 경로 | 확인할 결과 |
|---|---|
| 로그인 없는 실행 | 기존 홈·파일·정리·설정이 동작 |
| Google 로그인 취소 후 재시도 | 계정 없이 로컬 기능 이용 가능. 재시도 시 계정 선택 열림 |
| 계정 A 로그인, 앱 다시 시작 | 계정 A가 유지되고 Firebase Authentication에서 Google 제공자의 사용자로 조회됨 |
| 계정 전환 취소, 이어서 계정 B 선택 | 취소 시 A 유지. 완료 시 B 표시. Firebase UID 두 개가 구분됨 |
| 로그아웃 후 다시 로그인 | 로컬 앱 세션이 종료되고 이후 Google 로그인을 다시 선택 가능 |
| 구성 파일 누락·잘못된 프로젝트 | 클라우드 연결 불가 안내. 로컬 기능 이용 가능 |

참고 [Firebase Android Google 로그인](https://firebase.google.com/docs/auth/android/google-signin) · [Android Credential Manager](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation)

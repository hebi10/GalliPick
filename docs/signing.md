# 서명과 업데이트

GalliPick의 일반 debug·release 빌드는 같은 앱 전용 키를 사용합니다. 패키지는 `com.hebi.gallipick`, 이름은 `GalliPick`입니다. v0.1.6은 versionCode 7이며 별도 preview 패키지로 빌드하지 않습니다.

## 고정 서명 기준

v0.1.5에서 정한 서명 키를 유지합니다. 공개 인증서 지문은 `signing/certificate.sha256`에 고정하며, 실제 키스토어의 인증서가 이 값과 일치해야 빌드합니다. 파일 누락·비밀번호 오류·다른 인증서는 빌드를 중단합니다. 새로운 키를 자동 생성하거나 일반 SDK debug 키로 대체하지 않습니다.

키스토어와 실제 비밀번호 설정은 저장소 밖의 비공개 위치에 보관합니다. 개인 키·복구 ZIP·실제 설정 파일·Base64 값은 Git, 빌드 로그, 공개 artifact, 릴리스 파일에 넣지 않습니다.

## 로컬 서명 설정

빌드는 다음 순서로 설정 파일을 찾습니다.

1. 환경 변수 `GALLIPICK_SIGNING_PROPERTIES`가 지정한 파일. 이 경로를 지정했는데 파일이 없으면 다른 경로로 넘어가지 않습니다.
2. 저장소 루트의 `signing.properties`. 이 파일은 Git에서 제외합니다.
3. 사용자 홈의 `.config/GalliPick/signing.properties`.

설정 형식은 Java properties입니다. 아래는 필드 이름을 설명하는 자리표시자이며 실제 비밀 값이 아닙니다.

```properties
storeFile=<keystore-path>
storePassword=<private-store-password>
keyAlias=<key-alias>
keyPassword=<private-key-password>
storeType=<PKCS12-or-JKS>
```

`storeFile`, `storePassword`, `keyAlias`, `keyPassword`는 필수입니다. 상대 `storeFile` 경로는 설정 파일이 있는 디렉터리를 기준으로 해석합니다. `storeType`을 생략하면 `PKCS12`를 사용합니다. 비공개 복구 자료를 복원한 뒤 같은 공개 지문인지 확인하고 빌드합니다.

```bash
./gradlew :app:verifyGalliPickSigning :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

출력은 `app/build/outputs/apk/release/app-release.apk`입니다. 공개용 APK는 non-debuggable release이며 서명 검증을 거쳐 전달합니다.

## CI 검사와 공개 릴리스

일반 검사 job은 비공개 키 없이 명시적인 debug 검사 모드를 사용합니다.

```bash
./gradlew -PgallipickTestSigning=true testDebugUnitTest lintDebug assembleDebug connectedDebugAndroidTest
```

이 모드는 debug 검사에만 사용합니다. release 빌드는 항상 고정 키가 필요하며 임시 검사 모드로 실행하면 중단합니다. 일반 CI는 보고서를 보관하고 설치용 APK를 게시하지 않습니다.

공개 릴리스 job은 Actions secret **`GALLIPICK_SIGNING_BUNDLE_BASE64` 한 개**에서 비공개 복구 ZIP을 복원합니다. 이 값에는 키스토어와 비밀번호가 포함되므로 내용을 출력하지 않습니다. 허용된 파일만 runner 임시 디렉터리에 비공개 권한으로 복원하고, 종료 시 임시 서명 파일을 정리합니다. 이 secret은 서명·게시 job에만 전달합니다.

같은 커밋의 단위 검사·lint·Android 16/API 36 기기 검사가 성공한 뒤 고정 키로 빌드하고, APK 메타데이터·인증서·체크섬을 확인해 공개하도록 구성했습니다. 절차와 실제 게시 상태는 [공개 APK 릴리스](release.md)와 [검증 기록](verification.md)에서 확인합니다. 현재 원격 푸시·CI·공개 게시 결과는 대기 중입니다.

## 설치 호환성

- v0.1.5의 일반 release APK와 v0.1.6은 같은 키·같은 패키지를 사용합니다. versionCode가 6에서 7로 올라가는 업데이트 구성이며, 이번 업데이트를 위해 v0.1.5 앱을 삭제하지 않습니다.
- 이전의 다른 키로 서명된 개발 APK는 고정 키의 앱과 덮어쓰기 호환되지 않습니다. 이전 키에서 한 번 재설치했던 전환은 과거 버전에 해당하며, 그때 필터·보호 설정·분석 데이터가 초기화됩니다.
- `com.hebi.gallipick.preview`는 별도 패키지입니다. 그 앱의 설정을 일반 GalliPick으로 자동 이전하지 않습니다.
- 실제 기기의 설치·업데이트 검증 결과와 빌드 설정상 호환성은 구분해 기록합니다.

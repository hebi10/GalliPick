# 서명과 업데이트

GalliPick의 일반 debug·release 빌드는 같은 앱 전용 키를 사용합니다. 패키지는 `com.hebi.gallipick`, 이름은 `GalliPick`입니다. 현재 소스 v0.1.7은 versionCode 8이며 별도 preview 패키지로 빌드하지 않습니다. 이전 Play 업로드 v0.1.6은 versionCode 7입니다.

## 고정 서명 기준

현재 릴리스 기준은 사용자가 제공한 JKS 키입니다. 공개 인증서 지문은 `signing/certificate.sha256`에 고정하며, 실제 키스토어의 인증서가 이 값과 일치해야 빌드합니다. 파일 누락·비밀번호 오류·다른 인증서는 빌드를 중단합니다. 새로운 키를 자동 생성하거나 일반 SDK debug 키로 대체하지 않습니다.

```text
인증서 SHA-256: 3da9245ec543bba6348541e60346ebe8ed296bc0fb09eea4ae763138204d1bb1
```

v0.1.5와 이전 v0.1.6 로컬 APK의 개발 키는 과거 서명 기준입니다. 그 개발 키로 서명한 설치본은 이번 JKS 릴리스와 덮어쓰기 호환되지 않습니다.

키스토어와 실제 비밀번호 설정은 저장소 밖의 비공개 위치에 보관합니다. 개인 키·복구 ZIP·실제 설정 파일·Base64 값은 Git, 빌드 로그, 공개 artifact, 릴리스 파일에 넣지 않습니다.

## 로컬 서명 설정

빌드는 다음 순서로 설정 파일을 찾습니다.

1. 환경 변수 `GALLIPICK_SIGNING_PROPERTIES`가 지정한 파일. 이 경로를 지정했는데 파일이 없으면 다른 경로로 넘어가지 않습니다.
2. 저장소 루트의 `signing.properties`. 이 파일은 Git에서 제외합니다.
3. 사용자 홈의 `.config/GalliPick/signing.properties`.

설정 형식은 Java properties입니다. 아래는 필드 이름을 설명하는 자리표시자이며 실제 비밀 값이 아닙니다.

```properties
storeFile=gallipick-signing.jks
storePassword=<private-store-password>
keyAlias=<key-alias>
keyPassword=<private-key-password>
storeType=JKS
```

`storeFile`, `storePassword`, `keyAlias`, `keyPassword`는 필수입니다. 상대 `storeFile` 경로는 설정 파일이 있는 디렉터리를 기준으로 해석합니다. 현재 JKS에는 `storeType=JKS`를 명시하고 저장소 비밀번호와 개인 키 비밀번호를 각각 유지합니다. 두 비밀번호를 같은 값으로 간주하지 않습니다. `storeType` 생략 시의 `PKCS12` 기본값은 이전 형식 지원을 위한 것이며, 비공개 복구 자료를 복원한 뒤 현재 공개 지문과 일치하는지 확인합니다.

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

현재 복구 ZIP은 `signing.properties`, `certificate.sha256`, `RESTORE.txt`와 `gallipick-signing.jks`를 포함합니다. 이전 `gallipick-signing.p12` 형식도 지원하지만 키스토어는 둘 중 하나만 허용하며 인증서는 현재 고정 지문과 일치해야 합니다. 형식 지원이 이전 개발 키의 업데이트 호환성을 뜻하지는 않습니다.

같은 커밋의 단위 검사·lint·Android 16/API 36 기기 검사가 성공한 뒤 고정 키로 빌드하고, APK 메타데이터·인증서·체크섬을 확인해 공개하도록 구성했습니다. 소스는 새 공개 지문과 JKS 복구 형식을 사용하며 로컬 APK를 확인했습니다. Actions secret 갱신은 GitHub의 추가 본인 확인 창에서 대기 중이고 새 기준의 원격 CI 결과·공개 게시도 대기 중입니다. 실제 상태는 [공개 APK 릴리스](release.md)와 [검증 기록](verification.md)에서 확인합니다.

## 설치 호환성

- 기존 개발 키로 서명한 v0.1.5와 이전 v0.1.6 시험 APK는 사용자 제공 JKS와 서명이 다릅니다. 새 APK 공개 후 한 번 삭제·재설치해야 하며 로컬 필터·보호·분석 설정은 초기화될 수 있습니다.
- 앱 데이터 이전이나 서명 키 회전 증명을 수행하는 전환이 아닙니다. 이후 릴리스는 같은 JKS 키·패키지와 더 높은 versionCode를 유지합니다.
- `com.hebi.gallipick.preview`는 별도 패키지입니다. 그 앱의 설정을 일반 GalliPick으로 자동 이전하지 않습니다.
- 실제 기기의 설치·업데이트 검증 결과와 빌드 설정상 호환성은 구분해 기록합니다.

## 2026-11-03 AAB 빌드 확인

`Play Store bundle` 실행 [41584050395](https://github.com/hebi10/GalliPick/actions/runs/41584050395)은 서명 복구 단계에서 공개 인증서 지문 불일치를 검출하고 중단했습니다. 기존 Actions secret에 저장된 복구 자료가 현재 고정 인증서와 다릅니다. 현재 사용자 제공 JKS의 실제 인증서와 개인 키 접근, 새 복구 자료 구조는 비공개로 검증했습니다. Actions secret의 값 갱신은 사용자 입력·저장 단계에 대기 중이며, AAB 생성과 Play 업로드는 아직 완료되지 않았습니다.

## 별도 서명 경로 확인

2026-11-03 QuickCut 방식의 unsigned 준비 경로를 추가했습니다. [준비 빌드](https://github.com/hebi10/GalliPick/actions/runs/41586639777)의 정확한 AAB를 기존 검증 키로 비공개 임시 범위에서 서명했고, 기존 전체 AAB verifier에서 인증서·manifest·150개 payload 검증을 통과했습니다. 모든 앱 payload는 준비물과 동일하며 임시 키 파일은 정리했습니다. 이 경로는 기존 Actions secret을 갱신하지 않습니다. 직접 서명 workflow의 기존 secret 불일치와 구분합니다.

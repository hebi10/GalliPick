# 공개 APK 릴리스

Google Play 테스트 자동 제출은 [Play 배포 자동화](play-store/automation.md)의 별도 `Play testing deployment` workflow를 사용합니다. `v*` 태그는 항상 내부 테스트(`internal`)에 배포하고, 수동 실행은 `mode=build`·`target=internal`이 기본값입니다. 수동 제출에는 `mode=deploy`를 선택하며 비공개 테스트는 main에서 수동 `target=closed`로 선택한 경우에만 사용합니다. 내부 테스트에는 `PLAY_CLOSED_TRACK`이 필요하지 않습니다. 아래 공개 APK·AAB 준비 workflow도 유지하며, Play 자동 배포는 기존 서명키와 앱에 한정한 배포 계정 설정이 필요합니다.

[실제 check #2](https://github.com/hebi10/GalliPick/actions/runs/41787678213)는 WIF·Play 접근·기존 비공개 트랙 조회에 성공했지만 서명 Secret과 고정 인증서 불일치로 전체 실행은 실패했습니다. 첫 실제 제출 전 올바른 서명 Secret 복구와 이미 업로드한 code 8보다 높은 새 versionCode가 필요합니다. 내부 테스트 기본값 변경은 실제 배포나 Google 승인 완료를 의미하지 않습니다.

`Public APK release`는 검사를 통과한 소스에서 고정 키로 APK를 만들고 GitHub Releases에 게시하는 워크플로입니다. 일반 PR·main 검사에서는 임시 debug 서명만 사용하며 설치용 APK를 배포하지 않습니다.

## 서명 설정

저장소의 Actions secret `GALLIPICK_SIGNING_BUNDLE_BASE64`에는 비공개 `GalliPick-signing-recovery.zip`의 Base64를 저장합니다. 이 값은 키스토어와 비밀번호를 포함하므로 저장소 파일, 로그, 릴리스 본문, 공개 artifact에 넣지 않습니다. 비밀 값은 릴리스 게시 job에서만 사용하며 일반 검사 job에는 전달하지 않습니다.

현재 릴리스는 사용자 제공 JKS를 사용합니다. 복구 ZIP은 공통 파일 `signing.properties`, `certificate.sha256`, `RESTORE.txt`와 키스토어 `gallipick-signing.jks` 한 개로 구성합니다. `storeType=JKS`와 저장소·개인 키의 서로 다른 비밀번호를 각각 보존합니다. 이전 `gallipick-signing.p12`/`PKCS12` 형식도 지원하지만 두 키스토어를 함께 넣을 수는 없습니다.

워크플로는 runner 임시 디렉터리에 비공개 권한으로 복구하고, 공개 지문 `signing/certificate.sha256`과 실제 서명이 일치해야만 빌드합니다. 이전 개발 키의 복구 ZIP은 새 JKS 지문과 다르므로 현재 릴리스 기준이 아닙니다. job 종료 시 비공개 임시 파일을 지웁니다. 서명 키가 누락되거나 다르면 새 키를 만들지 않고 중단합니다.

## 수동 실행

1. 버전 변경과 릴리스 문서 `docs/releases/v버전.md`를 main에 반영합니다. versionCode도 기존 버전보다 높입니다.
2. Actions → Public APK release → Run workflow에서 main을 선택합니다.
3. `version`에 앱의 versionName과 일치하는 태그를 입력합니다. 예: `v0.1.6`.

실행은 시작 시점의 정확한 소스 커밋을 고정합니다. 단위 검사·lint와 Android 16/API 36 기기 검사가 모두 성공한 뒤 release job이 고정 키로 빌드합니다. APK 메타데이터·서명 검증 후 같은 커밋을 가리키는 태그를 생성합니다. 이미 태그가 있으면 그 대상 커밋이 일치해야 하며 덮어쓰지 않습니다.

## 태그 실행

앱 버전과 같은 `vMAJOR.MINOR.PATCH` 태그를 main에 포함된 커밋으로 push해도 같은 절차가 실행됩니다. 태그를 잘못된 버전이나 main 밖의 커밋으로 만들면 중단합니다. 수동 실행에서 GitHub Actions 토큰으로 만든 태그는 별도의 릴리스 실행을 다시 시작하지 않습니다.

## 게시 조건과 산출물

- 단위 검사·debug lint·API 36 실행 검사를 같은 소스 커밋에서 통과합니다.
- release lint와 고정 서명 검증을 통과합니다.
- 앱 패키지는 `com.hebi.gallipick`, 이름은 `GalliPick`, release는 non-debuggable이며 APK 버전은 소스·태그와 일치해야 합니다.
- APK 인증서는 저장소에 고정한 공개 SHA-256 한 개와 일치해야 합니다.
- 산출물은 `GalliPick-v버전-release.apk`와 `SHA256SUMS` 두 개입니다.
- 초안 릴리스에 파일을 올리고 다시 다운로드한 APK와 체크섬을 비교한 뒤 공개합니다.

같은 버전의 릴리스가 이미 있으면 공개·초안 여부와 관계없이 자동으로 수정하거나 파일을 교체하지 않습니다. 업로드·검증이 실패하면 공개하지 않고 초안을 남길 수 있으므로 실행 결과와 원격 상태를 확인한 뒤 처리합니다. 이미 공개한 버전의 APK를 교체하는 대신 새 버전을 사용합니다.

현재 JKS의 로컬 APK·복구 검증은 완료했고, 새 지문 원격 반영·Actions secret 갱신·CI·공개 게시는 대기 중입니다. 실제 통과 검사와 게시 링크는 [검증 기록](verification.md) 및 해당 Actions 실행·릴리스에서 확인합니다.

참고: [Actions workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax), [Actions secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets), [GitHub CLI release create](https://cli.github.com/manual/gh_release_create).

## Play Store AAB

`Play Store bundle`은 main의 정확한 커밋과 입력 버전을 확인하고 같은 고정 JKS로 `bundleRelease`를 빌드합니다. APK 공개 게시와 독립된 수동 workflow이며, Play 트랙을 자동 변경하지 않습니다.

빌드 전에 서명 복구 자료의 구조와 공개 지문을 확인하고 실제 개인 키·인증서를 검증합니다. 릴리스 단위 검사·lint 이후 bundletool 구조/manifest, 모든 payload의 JAR 서명, 패키지·버전·SDK·debuggable 설정과 비공개 키 노출을 확인합니다. 고정 버전 bundletool의 SHA-256도 검사합니다.

검증된 AAB, SHA256SUMS와 공개 빌드 근거만 artifact에 보관합니다. 비공개 서명 파일은 runner 임시 위치에만 복원하고 종료 시 삭제합니다. 일반 PR·main의 전체 기기 검사까지 통과한 소스를 Play Console에 수동 업로드하고, 업로드 수락과 실제 출시 상태를 구분하여 기록합니다.

## 키 없이 AAB 준비 후 별도 서명

QuickCut의 unsigned AAB artifact 흐름과 같이 `Play unsigned preparation`은 main·전용 작업 브랜치 push 또는 main 수동 실행에서 서명 전 AAB를 만듭니다. 별도 `playPreparation` build type은 non-debuggable이고 서명 설정이 없으며, 기존 debug/release의 고정 키 검사는 그대로 유지합니다.

이 workflow는 secret을 사용하지 않고 AAB 구조·패키지·버전·SDK·비공개 자료 부재 및 기존 서명 부재를 확인합니다. 검증한 공식 bundletool과 체크섬·공개 준비 근거도 artifact에 포함합니다. 산출물 이름은 `GalliPick-v버전-unsigned.aab`이고 상태는 `unsigned_preparation`/`signed:false`입니다.

준비물은 그대로 Play에 업로드하지 않습니다. 정확한 소스의 CI와 main 통합을 확인한 뒤, 기존 GalliPick 키를 비공개 임시 범위에서 복원해 JDK `jarsigner`로 별도 서명합니다. 이어서 기존 `verify_aab.py`의 모든 payload 서명·고정 인증서·manifest 검증을 통과한 `GalliPick-v버전-release.aab`만 업로드합니다. 임시 키 파일은 사용 후 삭제합니다. 이 과정은 GitHub의 저장된 자격 증명 값을 바꾸지 않습니다.

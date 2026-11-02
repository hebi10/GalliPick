# 공개 APK 릴리스

`Public APK release`는 검사를 통과한 소스에서 고정 키로 APK를 만들고 GitHub Releases에 게시하는 워크플로입니다. 일반 PR·main 검사에서는 임시 debug 서명만 사용하며 설치용 APK를 배포하지 않습니다.

## 서명 설정

저장소의 Actions secret `GALLIPICK_SIGNING_BUNDLE_BASE64`에는 비공개 `GalliPick-signing-recovery.zip`의 Base64를 저장합니다. 이 값은 키스토어와 비밀번호를 포함하므로 저장소 파일, 로그, 릴리스 본문, 공개 artifact에 넣지 않습니다. 비밀 값은 릴리스 게시 job에서만 사용하며 일반 검사 job에는 전달하지 않습니다.

복구 ZIP의 허용 파일은 `gallipick-signing.p12`, `signing.properties`, `certificate.sha256`, `RESTORE.txt` 네 개입니다. 워크플로는 runner 임시 디렉터리에 비공개 권한으로 복구하고, 공개 지문 `signing/certificate.sha256`과 실제 서명이 일치해야만 빌드합니다. job 종료 시 비공개 임시 파일을 지웁니다. 서명 키가 누락되거나 다르면 새 키를 만들지 않고 중단합니다.

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

현재 저장소의 워크플로 준비 상태와 실제 게시 완료는 구별합니다. 실제 통과 검사와 게시 링크는 해당 Actions 실행 및 릴리스에서 확인합니다.

참고: [Actions workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax), [Actions secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets), [GitHub CLI release create](https://cli.github.com/manual/gh_release_create).

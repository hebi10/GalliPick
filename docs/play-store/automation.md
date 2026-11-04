# Play 비공개 테스트 자동화

`Play closed testing`은 같은 소스에서 고정 키로 서명한 APK·AAB를 검증하고, 이미 존재하는 GalliPick 비공개 테스트 트랙에 AAB 업데이트를 제출합니다. 대상 패키지는 `com.hebi.gallipick` 하나입니다. 기존 `Public APK release`의 GitHub Releases APK 공개 흐름도 그대로 사용합니다.

## 실행 모드

| 실행 | 동작 | Play 인증 | 기기 검사 |
| --- | --- | --- | --- |
| 수동 `check` | 소스·고정 서명·Play 접근·기존 트랙 확인 | 필요 | 실행 안 함 |
| 수동 `build` — 기본값 | 단위 검사·release lint 후 서명 APK·AAB artifact 생성 | 불필요 | 실행 안 함 |
| 수동 `deploy` | 전체 검사·빌드 후 기존 비공개 트랙 제출 | 필요 | API 36 검사 필수 |
| `v*` 태그 push | `deploy`와 동일 | 필요 | API 36 검사 필수 |

수동 실행은 Actions → [Play closed testing](https://github.com/hebi10/GalliPick/actions/workflows/play-deploy.yml) → Run workflow에서 **main**을 선택합니다. 버전은 실행한 커밋의 `versionName`에서 읽습니다. 별도 태그를 생성하거나 소스 버전을 바꾸지 않습니다. `check`에서 서명 검사와 Play 설정 검사는 독립 job으로 실행하므로 Play 인증 누락과 기존 서명 불일치를 각각 확인할 수 있습니다.

모든 모드는 실행 시점의 정확한 소스 커밋을 고정하고, 최신으로 가져온 `origin/main`에 포함된 커밋인지 확인합니다. 태그 실행은 `vMAJOR.MINOR.PATCH` 형식, 소스 `versionName`과의 일치, 태그가 실제 실행 커밋을 가리키는지도 검사합니다. main 밖의 수동 실행이나 다른 소스를 가리키는 태그는 중단합니다.

Play 관련 실행은 `gallipick-play-publish` 그룹으로 직렬화하며 진행 중 실행을 자동 취소하지 않습니다. 같은 저장소의 다른 workflow나 Play Console의 수동 변경까지 잠그지는 않으므로 제출 중에는 Console에서 릴리스를 수정하지 않습니다.

## 최초 계정 설정

1. Google Cloud 프로젝트에서 **Google Play Android Developer API**를 활성화하고 배포 전용 서비스 계정을 준비합니다.
2. Play Console의 **사용자 및 권한**에 서비스 계정 이메일을 등록합니다. 계정 전체가 아니라 **GalliPick 앱만** 선택하고 **앱 정보 보기(읽기 전용)**와 **테스트 트랙으로 앱 출시** 권한을 부여합니다.
3. 관리자·프로덕션 출시·재무 권한은 부여하지 않습니다. 테스터 목록 관리·스토어 정보 수정 권한도 이 자동화에는 필요하지 않습니다.
4. 아래 두 인증 방법 중 하나를 설정하고, 실제 API에서 확인한 기존 비공개 트랙 ID를 repository Actions variable `PLAY_CLOSED_TRACK`에 저장합니다.

| 이름 | 종류 | 값 |
| --- | --- | --- |
| `GALLIPICK_SIGNING_BUNDLE_BASE64` | Actions secret | 현재 고정 JKS와 일치하는 기존 비공개 서명 복구 ZIP의 Base64 |
| `PLAY_CLOSED_TRACK` | Actions variable | 배포할 기존 비공개 테스트 트랙의 정확한 API ID |
| `PLAY_WORKLOAD_IDENTITY_PROVIDER` | Actions variable, WIF 사용 시 | Google Cloud Workload Identity Provider 전체 리소스 이름 |
| `PLAY_SERVICE_ACCOUNT` | Actions variable, WIF 사용 시 | Play Console 권한을 부여한 서비스 계정 이메일 |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Actions secret, JSON 대안 사용 시 | 해당 서비스 계정의 JSON 키 전체 |

권장 방식은 장기 JSON 키를 저장하지 않는 **Workload Identity Federation(WIF)**입니다. GitHub OIDC provider를 구성하고 `hebi10/GalliPick` 저장소 및 이 workflow의 main·버전 태그 실행으로 신뢰 조건을 제한합니다. 서비스 계정에 대한 필요한 `roles/iam.workloadIdentityUser` 바인딩을 해당 GitHub principal에만 부여하고 두 WIF variable을 설정합니다. WIF provider가 설정되어 있으면 WIF를 선택하며, 설정 오류를 JSON 키로 자동 우회하지 않습니다.

WIF를 사용할 수 없다면 `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` secret을 등록합니다. `google-github-actions/auth@v3`의 JSON 기반 access token 생성에 필요한 서비스 계정 자체의 `roles/iam.serviceAccountTokenCreator` 권한도 확인합니다. JSON 내용·개인 키·비밀번호를 저장소 파일이나 artifact에 넣지 않습니다.

인증은 공식 Google GitHub Action으로 수행하며 OAuth scope는 `https://www.googleapis.com/auth/androidpublisher` 하나입니다. `id-token: write`는 인증하는 preflight·deploy job에만 부여하고 나머지는 `contents: read`만 사용합니다. 자격 증명 파일을 생성하지 않고 action의 정리 기능도 켭니다. 빌드 완료 뒤 별도 deploy job에서 다시 인증하므로 빌드 전에 받은 토큰을 제출까지 보관하지 않습니다.

Console에 보이는 이름만으로 트랙 ID를 추정하지 않습니다. 현재 관찰한 비공개 테스트 표기는 `alpha`이며 repository variable도 `PLAY_CLOSED_TRACK=alpha`로 명시했습니다. **인증 준비 후 API가 반환한 기존 트랙 ID와 일치하는지 `check`로 확인해야 합니다.** 기본값·새 트랙 자동 생성은 없습니다. `production`, 공개 테스트, `internal`은 제출 대상이 아닙니다.

## 버전과 릴리스 순서

새 업로드마다 `app/build.gradle.kts`의 `versionCode`를 Play에 사용된 모든 버전보다 높여 main에 반영합니다. 자동화는 버전을 임의로 증가시키지 않습니다. 기존 draft·내부 테스트 업로드도 사용한 코드로 취급하며, 같은 코드를 덮어쓰거나 이미 있는 bundle을 자동으로 재사용하지 않습니다.

2026-11-04 확인 기준 비공개 테스트에는 0.1.6/code 7이 있고, 내부 테스트 초안에는 0.1.7/code 8 AAB가 이미 업로드되어 있습니다. 따라서 **다음 새 업로드는 최소 code 9**가 필요합니다. 그 사이 다른 업로드가 있으면 그보다 높은 코드를 사용합니다. 현재 v0.1.7/code 8 소스의 `build`는 파일을 만들 수 있지만 같은 코드를 다시 업로드하는 `deploy`는 중단되어야 합니다.

1. 새 버전·사용하지 않은 높은 versionCode와 `docs/play-store/release-notes-ko.txt`를 main에 반영합니다.
2. `check`로 고정 서명과 Play 설정을 확인합니다. 파일만 필요하면 기본 `build`를 실행합니다.
3. 자동 제출은 해당 커밋에 소스 버전과 같은 태그를 push합니다. 또는 main에서 수동 `deploy`를 실행합니다.
4. Actions의 배포 보고서와 Play Console의 검토·게시 상태를 확인합니다.

태그 예시는 다음과 같습니다. 버전은 실제 소스에 맞게 바꿉니다.

```bash
git tag v0.1.8
git push origin v0.1.8
```

같은 태그 push는 기존 `Public APK release`도 시작합니다. 새 버전의 `docs/releases/v버전.md`도 기존 공개 릴리스 절차에 맞게 준비합니다. Play workflow 자체에는 GitHub Releases나 태그를 쓰는 권한이 없습니다. GitHub Actions 토큰으로 다른 workflow가 생성한 태그는 별도 push workflow를 다시 시작하지 않을 수 있으므로 이 경로에 자동 연쇄 실행을 기대하지 않습니다.

## 검사와 산출물

서명 복구는 기존 `signing_bundle.py`를 사용하고 실제 개인 키 접근과 `signing/certificate.sha256` 고정 인증서를 검증합니다. 누락·잘못된 비밀번호·인증서 불일치 시 새 키를 만들지 않고 중단합니다. 비공개 파일은 runner 임시 범위에만 복원하고 성공·실패에 관계없이 정리합니다. 서명 job의 Gradle cache도 사용하지 않습니다.

APK와 AAB는 한 번의 release 빌드에서 만들며 단위 검사·release lint를 통과해야 합니다. `deploy`에서는 같은 커밋의 기존 Android verification과 API 36 기기 검사도 요구합니다. APK는 기존 메타데이터·서명 validator, AAB는 고정 bundletool 1.18.3/SHA-256과 기존 전체 payload 서명·manifest validator로 검증합니다.

실행 화면의 **Artifacts**와 build job 요약에서 다음 파일을 내려받습니다. 보관 기간은 14일입니다.

| Artifact | 파일 |
| --- | --- |
| `GalliPick-v버전-signed-apk` | 검증한 release APK, `SHA256SUMS` |
| `GalliPick-v버전-play-bundle` | 검증한 release AAB, `SHA256SUMS`, `manifest.json`, `provenance.json` |
| `GalliPick-v버전-play-preflight` | API 사전 검사에 도달한 경우의 `play-preflight.json` |
| `GalliPick-v버전-play-deployment` | 배포 시 생성한 `play-deployment.json` |

빌드 artifact에는 서명 복구 ZIP·키스토어·비밀번호 설정·인증 JSON·access token이 포함되지 않습니다. AAB provenance에는 소스 커밋·태그·bundle SHA-256·공개 인증서 지문·검증 근거만 기록합니다. 배포 job은 같은 실행에서 검증한 AAB artifact를 받아 manifest·hash·version을 확인한 뒤 업로드합니다.

## 제출 결과와 실패 처리

API 클라이언트는 기존 트랙과 이미 업로드한 APK·AAB 버전을 확인하고, 새 업로드 응답의 hash·version을 검증한 뒤 **선택한 기존 비공개 트랙만** 수정합니다. edit 검증 후 `ERROR_IF_IN_REVIEW` 정책으로 commit하고, 새 edit에서 결과를 다시 읽습니다. 진행 중 검토와 충돌할 때 검토 전송을 생략한 초안 저장으로 자동 전환하지 않습니다.

**API commit 수락은 Google 심사 승인이나 테스터 설치 가능 상태를 의미하지 않습니다.** 개발자 계정·앱 설정·정책·검토·관리형 게시 조건은 Console에서 확인합니다. 프로덕션·공개 테스트·테스터 목록·스토어 정보·정책 선언은 변경하지 않습니다.

업로드 또는 commit 중 연결이 끊기면 원격에서는 이미 수락했을 수 있습니다. 자동 재시도·자동 버전 변경·자동 재업로드를 하지 않습니다. Actions 보고서와 Play Console의 실제 bundle·트랙 상태를 먼저 확인합니다. 이미 올라간 버전은 재사용하지 않고 새 버전을 준비하며, 판단할 수 없는 commit 결과를 단순히 같은 실행 재시도로 해결하지 않습니다.

## 현재 설정 상태

2026-11-04 별도 앱 배포 전용 프로젝트에 GalliPick 전용 서비스 계정을 만들고, 해당 앱의 조회·테스트 출시 권한만 부여했습니다. GitHub 저장소에는 `PLAY_WORKLOAD_IDENTITY_PROVIDER`와 `PLAY_SERVICE_ACCOUNT`를 등록했으며 장기 JSON 개인 키는 생성하지 않았습니다. `PLAY_CLOSED_TRACK=alpha`는 실제 API 조회로 확인했습니다.

[실제 check #2](https://github.com/hebi10/GalliPick/actions/runs/41787678213)의 `play-preflight`가 성공했습니다. GitHub OIDC → 서비스 계정 토큰 발급 → Play 접근 → 기존 비공개 트랙 조회와 임시 edit 정리까지 확인했습니다. 동일 실행의 `signing-check`는 **기존 서명 복구 Secret과 고정 공개 인증서 지문 불일치**로 중단했습니다. 따라서 전체 실행은 실패 상태이며 APK·AAB 생성이나 Play 제출은 수행하지 않았습니다.

현재 남은 GalliPick 배포 차단 사항은 고정 JKS에 맞는 `GALLIPICK_SIGNING_BUNDLE_BASE64` 갱신입니다. 이후 새 업로드에는 이미 사용한 code 8보다 높은 버전 코드가 필요합니다. 새 인증 연결 자체는 서명키 변경이나 기존 설치본의 서명 변경을 뜻하지 않습니다.

[check #1](https://github.com/hebi10/GalliPick/actions/runs/41784363190)의 Play 인증 미설정 문제는 위 새 계정·WIF 연결로 해결했습니다. 구현·Android 검사 결과는 [검증 기록](../verification.md#2026-11-04--github-actions-비공개-테스트-자동화)에 기록했습니다.

참고: [Google Play API 시작](https://developers.google.com/android-publisher/getting_started), [Play Console 앱별 권한](https://support.google.com/googleplay/android-developer/answer/9844686), [Google GitHub 인증 action](https://github.com/google-github-actions/auth), [WIF 설정](https://github.com/google-github-actions/auth#workload-identity-federation-through-a-service-account), [Android Publisher tracks](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.tracks), [edit commit](https://developers.google.com/android-publisher/api-ref/rest/v3/edits/commit).

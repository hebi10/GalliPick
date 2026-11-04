# Play 테스트 배포 자동화

`Play testing deployment`는 같은 소스에서 고정 키로 서명한 APK·AAB를 검증하고, 기본 대상으로 GalliPick 내부 테스트(`internal`)에 AAB 업데이트를 제출합니다. 비공개 테스트(`closed`)는 수동 실행에서 선택한 경우에만 사용합니다. 대상 패키지는 `com.hebi.gallipick` 하나입니다. 기존 `Public APK release`의 GitHub Releases APK 공개 흐름도 그대로 사용합니다.

## 실행 모드

| 실행 | 동작 | Play 인증 | 기기 검사 |
| --- | --- | --- | --- |
| 수동 `check` | 소스·고정 서명·Play 접근·선택한 기존 테스트 트랙 확인 | 필요 | 실행 안 함 |
| 수동 `build` — 기본값 | 단위 검사·release lint 후 서명 APK·AAB artifact 생성 | 불필요 | 실행 안 함 |
| 수동 `deploy` | 전체 검사·빌드 후 선택한 기존 테스트 트랙 제출 | 필요 | API 36 검사 필수 |
| `v*` 태그 push | 전체 검사·빌드 후 **내부 테스트만** 제출 | 필요 | API 36 검사 필수 |

수동 실행은 Actions → [Play testing deployment](https://github.com/hebi10/GalliPick/actions/workflows/play-deploy.yml) → Run workflow에서 **main**을 선택합니다. `mode` 기본값은 `build`, `target` 기본값은 `internal`입니다. 기본 입력 그대로 실행하면 APK·AAB만 만들며 Play에 제출하지 않습니다. Play 업로드와 테스트 트랙 업데이트에는 `mode=deploy`를 직접 선택합니다.

| 대상 | 선택 방법 | 실제 API 트랙 |
| --- | --- | --- |
| 내부 테스트 — 기본값 | 수동 `target=internal` 또는 모든 `v*` 태그 push | `internal` 고정 |
| 비공개 테스트 | 수동 `target=closed` | repository variable `PLAY_CLOSED_TRACK`의 정확한 기존 트랙 ID |

`target`과 `mode`는 별도 설정입니다. 태그 실행은 수동 입력이나 `PLAY_CLOSED_TRACK` 값과 관계없이 항상 `deploy`·`internal`로 고정합니다. 내부 테스트에는 `PLAY_CLOSED_TRACK`이 필요하지 않습니다. 비공개 대상의 `check`·`deploy`에는 해당 variable이 필요하며, `build`에는 Play 설정이 없어도 됩니다. 비공개 트랙 값이 있으면 `production`, `beta`, `internal` 및 그 대소문자 변형, 잘못된 형식·form-factor 트랙은 인증이나 서명 Secret 접근 전에 거부합니다.

버전은 실행한 커밋의 `versionName`에서 읽습니다. 별도 태그를 생성하거나 소스 버전을 바꾸지 않습니다. `check`에서 서명 검사와 Play 설정 검사는 독립 job으로 실행하므로 Play 인증 누락과 기존 서명 불일치를 각각 확인할 수 있습니다.

모든 모드는 실행 시점의 정확한 소스 커밋을 고정하고, 최신으로 가져온 `origin/main`에 포함된 커밋인지 확인합니다. 태그 실행은 `vMAJOR.MINOR.PATCH` 형식, 소스 `versionName`과의 일치, 태그가 실제 실행 커밋을 가리키는지도 검사합니다. main 밖의 수동 실행이나 다른 소스를 가리키는 태그는 중단합니다.

Play 관련 실행은 `gallipick-play-publish` 그룹으로 직렬화하며 진행 중 실행을 자동 취소하지 않습니다. 같은 저장소의 다른 workflow나 Play Console의 수동 변경까지 잠그지는 않으므로 제출 중에는 Console에서 릴리스를 수정하지 않습니다.

## 최초 계정 설정

1. Google Cloud 프로젝트에서 **Google Play Android Developer API**를 활성화하고 배포 전용 서비스 계정을 준비합니다.
2. Play Console의 **사용자 및 권한**에 서비스 계정 이메일을 등록합니다. 계정 전체가 아니라 **GalliPick 앱만** 선택하고 **앱 정보 보기(읽기 전용)**와 **테스트 트랙으로 앱 출시** 권한을 부여합니다.
3. 관리자·프로덕션 출시·재무 권한은 부여하지 않습니다. 테스터 목록 관리·스토어 정보 수정 권한도 이 자동화에는 필요하지 않습니다.
4. 아래 두 인증 방법 중 하나를 설정합니다. 내부 테스트는 `internal`을 사용합니다. 비공개 테스트도 사용할 때만 실제 API에서 확인한 기존 비공개 트랙 ID를 repository Actions variable `PLAY_CLOSED_TRACK`에 저장합니다.

| 이름 | 종류 | 값 |
| --- | --- | --- |
| `GALLIPICK_SIGNING_BUNDLE_BASE64` | Actions secret | 현재 고정 JKS와 일치하는 기존 비공개 서명 복구 ZIP의 Base64 |
| `PLAY_CLOSED_TRACK` | Actions variable, 수동 `target=closed`의 `check`·`deploy` 시 필수 | 배포할 기존 비공개 테스트 트랙의 정확한 API ID. 내부 테스트에는 불필요 |
| `PLAY_WORKLOAD_IDENTITY_PROVIDER` | Actions variable, WIF 사용 시 | Google Cloud Workload Identity Provider 전체 리소스 이름 |
| `PLAY_SERVICE_ACCOUNT` | Actions variable, WIF 사용 시 | Play Console 권한을 부여한 서비스 계정 이메일 |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Actions secret, JSON 대안 사용 시 | 해당 서비스 계정의 JSON 키 전체 |

권장 방식은 장기 JSON 키를 저장하지 않는 **Workload Identity Federation(WIF)**입니다. GitHub OIDC provider를 구성하고 `hebi10/GalliPick` 저장소 및 이 workflow의 main·버전 태그 실행으로 신뢰 조건을 제한합니다. 서비스 계정에 대한 필요한 `roles/iam.workloadIdentityUser` 바인딩을 해당 GitHub principal에만 부여하고 두 WIF variable을 설정합니다. WIF provider가 설정되어 있으면 WIF를 선택하며, 설정 오류를 JSON 키로 자동 우회하지 않습니다.

WIF를 사용할 수 없다면 `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` secret을 등록합니다. `google-github-actions/auth@v3`의 JSON 기반 access token 생성에 필요한 서비스 계정 자체의 `roles/iam.serviceAccountTokenCreator` 권한도 확인합니다. JSON 내용·개인 키·비밀번호를 저장소 파일이나 artifact에 넣지 않습니다.

인증은 공식 Google GitHub Action으로 수행하며 OAuth scope는 `https://www.googleapis.com/auth/androidpublisher` 하나입니다. `id-token: write`는 인증하는 preflight·deploy job에만 부여하고 나머지는 `contents: read`만 사용합니다. 자격 증명 파일을 생성하지 않고 action의 정리 기능도 켭니다. 빌드 완료 뒤 별도 deploy job에서 다시 인증하므로 빌드 전에 받은 토큰을 제출까지 보관하지 않습니다.

내부 테스트 API ID는 `internal`로 고정합니다. 비공개 테스트는 Console 표시 이름으로 ID를 추정하지 않으며, 현재 repository variable `PLAY_CLOSED_TRACK=alpha`는 실제 API 조회로 확인한 기존 ID입니다. 비공개 대상을 사용할 때는 `mode=check`·`target=closed`로 확인합니다. 두 대상 모두 기존 트랙만 사용하며 새 트랙을 만들지 않습니다. `production`·공개 테스트(`beta`)와 form-factor 트랙은 제출 대상이 아닙니다. CLI에서도 `--track`을 생략하면 `internal`, 명시하면 `--track alpha` 등 기존 비공개 트랙을 선택합니다.

## 버전과 릴리스 순서

새 업로드마다 `app/build.gradle.kts`의 `versionCode`를 Play에 사용된 모든 버전보다 높여 main에 반영합니다. 자동화는 버전을 임의로 증가시키지 않습니다. 기존 draft·내부 테스트 업로드도 사용한 코드로 취급하며, 같은 코드를 덮어쓰거나 이미 있는 bundle을 자동으로 재사용하지 않습니다.

2026-11-04 확인 기준 비공개 테스트에는 0.1.6/code 7이 있고, 내부 테스트 초안에는 0.1.7/code 8 AAB가 이미 업로드되어 있습니다. 따라서 **다음 새 업로드는 최소 code 9**가 필요합니다. 그 사이 다른 업로드가 있으면 그보다 높은 코드를 사용합니다. 현재 v0.1.7/code 8 소스는 올바른 서명 설정을 복구하면 `build`로 파일을 만들 수 있지만 같은 코드를 다시 업로드하는 `deploy`는 대상 트랙에 관계없이 중단되어야 합니다.

1. 새 버전·사용하지 않은 높은 versionCode와 `docs/play-store/release-notes-ko.txt`를 main에 반영합니다.
2. 기본 `target=internal`의 `check`로 고정 서명과 내부 테스트 접근을 확인합니다. 파일만 필요하면 기본 `build`를 실행합니다.
3. 내부 테스트 자동 제출은 해당 커밋에 소스 버전과 같은 태그를 push합니다. 또는 main에서 수동 `mode=deploy`·`target=internal`을 실행합니다. 비공개 테스트 제출은 main에서 수동 `mode=deploy`·`target=closed`를 선택합니다.
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

API 클라이언트는 기존 트랙과 이미 업로드한 APK·AAB 버전을 확인하고, 새 업로드 응답의 hash·version을 검증한 뒤 **선택한 기존 테스트 트랙 하나만** 수정합니다. 기본 내부 테스트 배포는 `internal`만, 수동 비공개 배포는 `PLAY_CLOSED_TRACK`에 지정한 트랙만 수정합니다. edit 검증 후 `ERROR_IF_IN_REVIEW` 정책으로 commit하고, 새 edit에서 결과를 다시 읽습니다. 진행 중 검토와 충돌할 때 검토 전송을 생략한 초안 저장으로 자동 전환하지 않습니다.

**API commit 수락은 Google 심사 승인이나 테스터 설치 가능 상태를 의미하지 않습니다.** 개발자 계정·앱 설정·정책·검토·관리형 게시 조건은 Console에서 확인합니다. 프로덕션·공개 테스트·테스터 목록·스토어 정보·정책 선언은 변경하지 않습니다.

업로드 또는 commit 중 연결이 끊기면 원격에서는 이미 수락했을 수 있습니다. 자동 재시도·자동 버전 변경·자동 재업로드를 하지 않습니다. Actions 보고서와 Play Console의 실제 bundle·트랙 상태를 먼저 확인합니다. 이미 올라간 버전은 재사용하지 않고 새 버전을 준비하며, 판단할 수 없는 commit 결과를 단순히 같은 실행 재시도로 해결하지 않습니다.

## 현재 설정 상태

2026-11-04 별도 앱 배포 전용 프로젝트에 GalliPick 전용 서비스 계정을 만들고, 해당 앱의 조회·테스트 출시 권한만 부여했습니다. GitHub 저장소에는 `PLAY_WORKLOAD_IDENTITY_PROVIDER`와 `PLAY_SERVICE_ACCOUNT`를 등록했으며 장기 JSON 개인 키는 생성하지 않았습니다. `PLAY_CLOSED_TRACK=alpha`는 실제 API 조회로 확인했습니다.

[실제 check #2](https://github.com/hebi10/GalliPick/actions/runs/41787678213)의 `play-preflight`가 성공했습니다. GitHub OIDC → 서비스 계정 토큰 발급 → Play 접근 → 기존 비공개 트랙 조회와 임시 edit 정리까지 확인했습니다. 동일 실행의 `signing-check`는 **기존 서명 복구 Secret과 고정 공개 인증서 지문 불일치**로 중단했습니다. 따라서 전체 실행은 실패 상태이며 APK·AAB 생성이나 Play 제출은 수행하지 않았습니다.

[내부 테스트 check #3](https://github.com/hebi10/GalliPick/actions/runs/41790941768)을 변경된 main에서 기본 `target=internal`로 실행했습니다. `prepare`와 `play-preflight`가 성공해 내부 테스트 API ID 선택·WIF 인증·해당 트랙 조회·임시 edit 정리를 확인했습니다. `signing-check`는 같은 인증서 불일치로 실패했고 build·deploy는 실행되지 않았습니다. 내부 테스트 기본값과 비공개 테스트 수동 선택은 [PR #8](https://github.com/hebi10/GalliPick/pull/8)로 반영했습니다.

2026-11-04 원본 키를 비공개 백업에서 찾아 현재 고정 인증서와 일치함을 확인했습니다. 과거 사용자가 전달한 설정으로 JKS·개인 키를 열고 실제 전자서명·공개키 검증을 통과했습니다. 새 복구 ZIP은 기존 `signing_bundle.py`로 복원한 뒤 같은 검증을 다시 통과했고 독립 검토도 완료했습니다. 새 키는 만들지 않았으므로 업로드키 재설정이 필요하지 않습니다. 현재 GitHub Secret 값은 아직 갱신하지 않았으며, 마지막 Actions check #3의 실패 상태는 유지됩니다. 검증된 복구 ZIP 전체의 Base64로 Secret을 교체한 뒤 새 `check`를 실행해야 합니다. 복구 파일·비밀번호·개인키·Base64는 저장소에 포함하지 않습니다.

현재 남은 GalliPick 배포 차단 사항은 고정 JKS에 맞는 `GALLIPICK_SIGNING_BUNDLE_BASE64` 갱신, Console의 기존 내부 테스트 초안 처리, 이미 사용한 code 8보다 높은 새 버전 코드 준비입니다. 대상 트랙에 초안이 남아 있으면 새 코드여도 자동화는 제출을 중단하며 초안을 자동으로 덮어쓰지 않습니다. check #2는 기존 비공개 트랙, check #3은 내부 트랙 접근을 검증했습니다. 두 실행 모두 실제 서명 빌드·배포 성공 기록은 아닙니다. 새 인증 연결 자체는 서명키 변경이나 기존 설치본의 서명 변경을 뜻하지 않습니다.

[check #1](https://github.com/hebi10/GalliPick/actions/runs/41784363190)의 Play 인증 미설정 문제는 위 새 계정·WIF 연결로 해결했습니다. 구현·Android 검사 결과는 [검증 기록](../verification.md#2026-11-04--github-actions-비공개-테스트-자동화)에 기록했습니다.

참고: [Google Play API 시작](https://developers.google.com/android-publisher/getting_started), [Play Console 앱별 권한](https://support.google.com/googleplay/android-developer/answer/9844686), [Google GitHub 인증 action](https://github.com/google-github-actions/auth), [WIF 설정](https://github.com/google-github-actions/auth#workload-identity-federation-through-a-service-account), [Android Publisher tracks](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.tracks), [edit commit](https://developers.google.com/android-publisher/api-ref/rest/v3/edits/commit).

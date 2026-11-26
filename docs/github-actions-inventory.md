# GalliPick GitHub Actions 인벤토리

2026-11-23 기준 [GP-CI-01·02](https://github.com/hebi10/GalliPick/issues/131)에서 `4e3ddc0`의 워크플로 29개를 검토했습니다. 일회성 복구 1개를 정리하면 28개입니다. 별도 [빌드 벤치마크 PR #181](https://github.com/hebi10/GalliPick/pull/181)의 1개까지 반영하면 아래 목록의 29개가 됩니다.

실행 조건은 현재 YAML을 기준으로 합니다. 실행 링크는 조회한 이력에서 확인된 사례이며 예전 실행의 조건은 현재와 다를 수 있습니다. 과거 CI 성공 기록은 현재 커밋의 검증, Play 검토 완료 또는 테스터 설치를 보증하지 않습니다.

## 자동 검사와 검증

| 워크플로 | 실행 조건 | 역할과 호출 관계 | 인증 | 확인한 실행 (UTC) |
| --- | --- | --- | --- | --- |
| `android.yml` | PR, 문서만 변경한 경우를 제외한 main push, 수동, `workflow_call` | Android 단위 테스트·Lint·디버그 빌드. `public-release`와 레거시 `play-deploy`에 호출 연결. OPS-03·09가 성공 이력 확인 | — | [11-23 성공](https://github.com/hebi10/GalliPick/actions/runs/45138409724) |
| `device-tests.yml` | 수동, `workflow_call` | Android 전체 기기 QA. OPS-09가 수동 디스패치. `public-release`와 레거시 `play-deploy`에 호출 연결. OPS-03이 성공 이력 확인 | — | [11-23 성공](https://github.com/hebi10/GalliPick/actions/runs/45138526107) |
| `cloud-verification.yml` | Firebase 관련 PR·main push | Firebase Functions 검사, 배포 없음 | — | [11-23 성공](https://github.com/hebi10/GalliPick/actions/runs/45138066973) |
| `gp-exit-emulator-qa.yml` | 계정 삭제 관련 PR, 수동 | 합성 계정의 삭제를 Firebase 에뮬레이터에서 검증 | — | [11-23 성공](https://github.com/hebi10/GalliPick/actions/runs/45136023366) |
| `gp-admin-readonly.yml` | 관리자 웹·관련 함수 PR, 수동 | Hosting 구성과 관리자 웹의 읽기 전용 계약 검증 | — | [11-20 성공](https://github.com/hebi10/GalliPick/actions/runs/44677628231) |
| `gp-checkout-readonly-audit.yml` | 관련 PR, 수동 | PR에서 오프라인 계약만 검사. 승인 입력과 정확한 main SHA가 있는 수동 실행에서 Play 구독·Firebase 함수 메타데이터 조회 | Play WIF, Firebase 읽기 전용 WIF | [11-20 오프라인 성공](https://github.com/hebi10/GalliPick/actions/runs/44697708231), [수동 실패](https://github.com/hebi10/GalliPick/actions/runs/44701173321) |
| `gp-cloud-internal-preflight-readonly.yml` | main 수동 | 운영 프로젝트의 Play 서명용 Android·웹 OAuth 소유권과 현재 main 버전의 Play 업로드 가능 여부 조회 | 운영 Cloud 설정, Play WIF | [11-16 이전 개발용 검사](https://github.com/hebi10/GalliPick/actions/runs/43827102310) |
| `gp-perf-00-sqlite.yml` | 성능 스크립트 관련 PR, 수동 | 합성 SQLite 데이터 성능 기준치 | — | [11-18 성공](https://github.com/hebi10/GalliPick/actions/runs/44255830841) |
| `gp-build-benchmark.yml` | Android 빌드 설정·벤치마크 관련 PR, 수동 | Android package benchmarks. 세 빌드 프로필의 APK·AAB 크기, 런타임 의존성과 R8 결과를 기록. 관련 PR 및 `measure_device`를 선택한 수동 실행은 Play Cloud 두 시험 APK의 설치 공간·시작 시간·기본 화면 동작을 비교 | — | [11-23 PR 실행](https://github.com/hebi10/GalliPick/actions/runs/45151637426) |
| `cloud-files-07-visual-qa.yml` | 지정된 기능 브랜치 대상 관련 PR, 수동 | Cloud 갤러리 캡처용 APK와 합성 파일 기반 시각 QA | — | [11-17 성공](https://github.com/hebi10/GalliPick/actions/runs/44055565983) |
| `stage10e-captures.yml` | main 대상 PR, 수동 | 라이트·다크 네이티브 시각 QA. PR의 브랜치가 지정 패턴에 해당할 때만 빌드 | — | [11-08 성공](https://github.com/hebi10/GalliPick/actions/runs/42490897036), [11-23 건너뜀](https://github.com/hebi10/GalliPick/actions/runs/45137361864) |
| `ui-captures.yml` | 지정된 디자인 브랜치 push, 수동 | 화면 크기별 UI 검증과 네이티브 캡처 | — | [11-08 성공](https://github.com/hebi10/GalliPick/actions/runs/42469129960) |
| `ops04-safety.yml` | OPS-04 관련 PR, 수동 | 내부 게시의 AAB 증빙·승인 오프라인 검증 | — | [11-18 성공](https://github.com/hebi10/GalliPick/actions/runs/44253532044) |
| `ops05-safety.yml` | OPS-05 관련 PR | 동일 버전 비공개 테스트 승격의 오프라인 검증 | — | [11-13 성공](https://github.com/hebi10/GalliPick/actions/runs/43364888634) |
| `ops06-store-safety.yml` | OPS-06 관련 PR·main push | 스토어 이미지·아이콘 게이트의 오프라인 검증 | — | [11-13 성공](https://github.com/hebi10/GalliPick/actions/runs/43369906287) |
| `ops07-safety.yml` | PR, 수동 | Play 변경 경로와 OPS 게이트 통합 검사 | — | [11-23 성공](https://github.com/hebi10/GalliPick/actions/runs/45137361817) |
| `ops08-final-policy.yml` | OPS-07·08 관련 PR, main push, 수동 | 현재 소스와 전체 워크플로의 릴리스 정책 검증 | — | [11-23 성공](https://github.com/hebi10/GalliPick/actions/runs/45138409935) |
| `ops09-safety.yml` | PR, OPS-09·서명 빌드 관련 main push | 1회 승인 내부 테스트 파이프라인의 오프라인 계약 검사 | — | [11-23 성공](https://github.com/hebi10/GalliPick/actions/runs/45137361747) |

## 수동 릴리스와 Play 조회

이 표의 모든 워크플로는 수동 디스패치로 시작합니다. 다른 워크플로가 대신 디스패치하는 경우는 역할 칸에 표시했습니다.

| 워크플로 | 역할과 호출 관계 | 인증 및 승인 | 확인한 실행 (UTC) |
| --- | --- | --- | --- |
| `internal-release-once.yml` | OPS-09. 기본 CI 확인 후 `device-tests` → `release-build`와 실제 빌더 → `play-internal-release` 순서 | GitHub Actions 쓰기 권한. 내부 테스트 1회 승인과 동결 main SHA | [11-21 성공](https://github.com/hebi10/GalliPick/actions/runs/44888619144) |
| `release-build.yml` | OPS-03. 승인과 동일 SHA의 Android 기본 CI·전체 기기 QA를 확인하고 `play-bundle` 디스패치. OPS-09에서 호출 | GitHub Actions 쓰기 권한. 승인된 배치·main SHA·버전 | [11-21 성공](https://github.com/hebi10/GalliPick/actions/runs/44890071069) |
| `play-bundle.yml` | 고정 인증서로 AAB 빌드 및 SHA-256·매니페스트 업로드. OPS-03의 빌더 | 서명 키, 선택한 Cloud 설정. 운영 Cloud는 승인된 main SHA 필요 | [11-21 성공](https://github.com/hebi10/GalliPick/actions/runs/44890081403) |
| `play-internal-release.yml` | OPS-04. 승인된 기존 AAB 검증 및 internal 게시. OPS-09에서 호출 | Play WIF, `google-play` 환경. 기대 SHA·빌드 run ID·해시·버전 및 게시 승인 | [11-21 성공](https://github.com/hebi10/GalliPick/actions/runs/44890733483) |
| `play-closed-release.yml` | OPS-05. 기존 internal AAB를 검사하고 승인 시 동일 code를 기존 비공개 트랙에 승격 | Play WIF 또는 JSON. 배치·기대 SHA·해시·기존 트랙 및 승격 승인 | [11-21 code37 조회 성공](https://github.com/hebi10/GalliPick/actions/runs/44891091789) |
| `play-alpha-status-readonly.yml` | main에서 기존 alpha와 전체 Play 트랙·업로드 코드 조회, 조회 보고서만 생성 | Play WIF와 `PLAY_CLOSED_TRACK=alpha` | [11-17 이전 파일명으로 성공](https://github.com/hebi10/GalliPick/actions/runs/44156727727) |
| `play-deploy.yml` | 레거시 Play·서명 설정 확인 및 서명 빌드. `deploy`는 초기 가드에서 거부 | 서명 키, Play 확인용 WIF 또는 JSON, 비공개 확인 시 `PLAY_CLOSED_TRACK` | [11-08 당시 경로 성공](https://github.com/hebi10/GalliPick/actions/runs/42493981770) |
| `play-preparation.yml` | 배포하지 않는 unsigned AAB 준비 및 검증 | — | [11-13 당시 push 경로 성공](https://github.com/hebi10/GalliPick/actions/runs/43377945798) |
| `play-listing-images.yml` | 한국어 스토어 소개 이미지 검증·업로드 | 업로드에 Play WIF 또는 JSON과 OPS-06 별도 승인 | [11-08 당시 push 경로 성공](https://github.com/hebi10/GalliPick/actions/runs/42437395564) |
| `play-store-icon.yml` | 생성한 아이콘 검증·한국어 등록정보 업데이트 | 업로드에 Play WIF 또는 JSON과 OPS-06 별도 승인 | [11-05 당시 push 경로 성공](https://github.com/hebi10/GalliPick/actions/runs/41964686350) |
| `public-release.yml` | Android 기본 검사·기기 QA를 호출하고 서명 APK를 GitHub Releases에 게시 | 고정 서명 키, GitHub 콘텐츠 쓰기 권한, 정확한 버전과 명시적 승인 | [11-02 취소](https://github.com/hebi10/GalliPick/actions/runs/41392108200) |

`—`는 GitHub의 기본 작업 토큰 외에 Play·Firebase·서명 비밀 입력을 참조하지 않는 경우입니다. Play WIF는 `PLAY_WORKLOAD_IDENTITY_PROVIDER`와 `PLAY_SERVICE_ACCOUNT`를 사용합니다. JSON 방식은 `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`을 참조합니다. Firebase 읽기 전용 WIF는 `FIREBASE_READONLY_WORKLOAD_IDENTITY_PROVIDER`와 `FIREBASE_READONLY_SERVICE_ACCOUNT`를 사용합니다. 서명 키와 Cloud 설정은 각각 `GALLIPICK_SIGNING_BUNDLE_BASE64`, `GALLIPICK_PLAY_CLOUD_CONFIG_BASE64` 또는 `GALLIPICK_PLAY_PRODUCTION_CLOUD_CONFIG_BASE64`를 참조합니다.

스토어 이미지·아이콘은 OPS-06 별도 승인 대상입니다. 비공개 테스트는 이미 internal에서 확인한 버전의 OPS-05 별도 승격입니다. Firebase 서버 배포와 Play 정식 출시는 각각 별도 승인 대상입니다. 자세한 정책은 [AGENTS.md](../AGENTS.md)를 따릅니다.

## GP-CI-02 정리 근거

- `play-v026-resumable-recovery.yml`은 **v0.2.6/code17**의 alpha 업로드 전용입니다. [11-07 실행](https://github.com/hebi10/GalliPick/actions/runs/42381354767)에서 해당 업로드 단계가 성공했고 현재 코드에서는 `false` 가드로 복구 job이 비활성화되어 있었습니다. AAB run·해시·code17에 고정되어 있으며 그 이후 버전이 있는 경우도 거부합니다. 저장소 내 호출자가 없어 활성 목록에서 제거했습니다.
- `play-status-v026-readonly.yml`은 이전 버전명이 파일과 보고서에 남아 있었지만 실제로 기존 alpha와 전체 versionCode를 조회합니다. 같은 조회를 제공하는 `play-deploy`의 check는 서명 키 점검도 수행하며, OPS-05의 check는 기존 내부 테스트 AAB 증빙을 요구합니다. 서명 키 없이 alpha를 조회하는 역할을 `play-alpha-status-readonly.yml`에 계승하고, 변경 시 push 실행은 제거했습니다. Play 조회 도구는 임시 edit를 만든 뒤 폐기하며 트랙이나 AAB를 커밋하지 않습니다.
- `gp-cloud-internal-preflight-readonly.yml`은 운영 Firebase 설정에 등록된 Play 서명용 Android OAuth와 웹 OAuth가 운영 프로젝트 소유인지 확인합니다. 후보 버전은 main의 Gradle 선언에서 읽으며, 이미 사용한 versionCode 또는 미완료 내부 릴리스가 있으면 차단합니다. 과거 versionCode 24 개발용 검사는 위 실행 기록에 남아 있습니다. 이 워크플로는 Play 정책·실기기 로그인 검증 및 승인된 서명 빌드와 게시 절차와 별개입니다.

과거 두 YAML은 [정리 전 커밋](https://github.com/hebi10/GalliPick/tree/4e3ddc0ff89e8462e3a9b58e0e5206a1dc52ac0f/.github/workflows)에서 열 수 있습니다. 원본이 필요하면 해당 커밋의 파일을 Git에서 확인합니다. 활성 경로 복원이 필요한 경우 이전 파일의 범위와 승인 가드를 검토한 PR을 사용하고, 역사적 코드17 복구 실행 기록은 위 Actions 링크를 참조합니다.

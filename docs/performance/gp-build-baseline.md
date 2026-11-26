# GP-BUILD-01~02 패키지 기준선과 R8 실험

## 측정 프로필

| 프로필 | Gradle 변형 | 소스와 라이브러리 | 서명 |
| --- | --- | --- | --- |
| `localOnly` | `releaseBenchmark` | 로컬 클라우드 대체 구현, 광고 비활성 | 없음 |
| `devCloud` | `debug` | 클라우드 코드와 의존성, 디버그 Compose 도구, 광고 비활성 | 디버그 테스트 키 |
| `playCloud` | `releaseBenchmark` | Play Cloud와 같은 클라우드 코드와 의존성, 광고 미리보기 | 없음 |

`releaseBenchmark`는 명시적으로 요청할 때만 생성되며 패키지 ID는 `com.hebi.gallipick.benchmark`입니다. `devCloud`와 `playCloud`의 Firebase 설정은 비어 있으므로 로그인이나 서버 통신은 발생하지 않습니다. 두 릴리스형 프로필의 광고 SDK, UMP, Billing은 기존 공통 의존성을 그대로 사용합니다. Android 매니페스트에서 광고 ID 권한을 제거해도 SDK 의존성은 남습니다. `GalliAds`의 공통 코드가 광고 SDK와 UMP를 참조하며, Billing 호출은 클라우드 구현에 있습니다.

## 실행과 결과

기존 산출물과 Firebase 구성 파일이 없는 별도 Git worktree에서 JDK 17, Android SDK 플랫폼 36, Build Tools 35.0.0, Python 3.9 이상을 준비합니다. 저장소의 Gradle Wrapper가 Gradle 8.13을 사용합니다. 각 측정 전에 `:app:clean`을 실행하므로 앱 빌드 폴더의 기존 산출물이 삭제됩니다.

```sh
python3 scripts/performance/gp_build.py --output build/gp-build-baseline
python3 scripts/performance/gp_build.py --profiles localOnly playCloud --r8 --output build/gp-build-r8
```

`metrics.json`에 커밋, 작업 트리 변경 여부, APK와 AAB의 파일 크기(byte), 각 DEX의 메서드 참조 수와 합계를 기록합니다. 여러 DEX에 중복된 메서드 참조는 각 파일의 수치에 포함됩니다. APK는 전체 APK 파일 크기이며 AAB는 업로드용 컨테이너 크기입니다. Play가 기기별로 전달하는 앱 크기와 설치 후 저장 공간은 기기에서 별도로 측정합니다. 각 프로필의 Gradle `RuntimeClasspath` 보고서는 직접 및 전이 의존성을 담습니다. 비교는 같은 커밋과 도구 버전의 동일 프로필에서 수행합니다.

GitHub Actions의 **Android package benchmarks**는 빌드 설정이나 측정 코드가 바뀐 PR에서 같은 측정을 수행합니다. 수동 실행도 가능하며, `package-metrics-*` 아티팩트에 JSON 결과와 의존성 보고서만 저장합니다. [워크플로 실행 목록](https://github.com/hebi10/GalliPick/actions/workflows/gp-build-benchmark.yml)

## R8 검증

`--r8`은 `releaseBenchmark` 전용 `gallipickMinifyBenchmark=true`를 지정합니다. R8 코드 축소와 리소스 축소가 함께 적용되며 Android Gradle Plugin의 `proguard-android-optimize.txt`, 앱 전용 `app/proguard-rules.pro` 및 라이브러리 소비자 규칙을 사용합니다. 앱 전용 keep 규칙은 재현된 오류와 `app/build/outputs/mapping/releaseBenchmark/` 분석에 근거해 추가합니다. 다음 프로필 빌드에서 이 디렉터리를 지우므로 분석에 사용할 매핑은 각 빌드 직후 복사합니다.

```sh
./gradlew -PgallipickBuildBenchmark=playCloud -PgallipickMinifyBenchmark=true :app:assembleReleaseBenchmark :app:bundleReleaseBenchmark
```

## GP-R8-02 릴리스 설정 전환 (검증 브랜치)

- 실제 `release`의 코드 축소·난독화·최적화 및 리소스 축소를 활성화하고, 최적화 시험 변형과 동일한 규칙 파일을 사용합니다. `gallipickMinifyBenchmark`는 기존대로 시험 변형에만 적용됩니다.
- `debug` 및 기존의 미서명 로컬 전용 `playPreparation`은 축소하지 않습니다. APK/AAB 비교의 비최적화 기준선도 유지합니다. AGP 8.13.2의 기존 R8 동작을 사용하며 새로운 의존성·AGP 업그레이드·전역 keep 규칙을 추가하지 않습니다.
- `:app:verifyGalliPickR8Configuration`은 실제 Gradle DSL의 각 빌드 유형별 축소 플래그와 적용 규칙 목록을 확인하며 운영 키나 Firebase 비밀값 없이 실행합니다. 기존 패키지 벤치마크의 각 프로필에서 검사합니다.
- GP-R8-01 병합, GP-R8-03~04 호환성 및 운영 연동 회귀 검증 전에는 이 변경을 main에 병합하거나 배포하지 않습니다. GP-R8-05에서 Google Play 최적화 비율을 별도 확인합니다.

## GP-R8-03 이름 기반 런타임 호환성 계약

- WorkManager의 기존 작업 DB는 Worker 클래스의 정규 이름을 저장합니다. 앱의 4개 예약 Worker의 원래 클래스 이름과 (Context, WorkerParameters) 공개 생성자를 명시적으로 보존합니다. 메서드 전체 및 패키지 전체 keep은 사용하지 않습니다.
- Room은 생성된 구현 클래스를 이름으로 찾아 생성하는 경로를 사용합니다. 라이브러리의 consumer rule을 사용하고 `MediaDatabase`, `MediaDatabase_Impl` 및 구현 클래스 공개 무인자 생성자가 실제 APK에 남아 있는지 검사합니다.
- `gp_r8_compat.py`는 최적화된 APK에서 매핑으로 이름을 복원하지 않은 DEX 클래스·생성자 정의를 확인하며 비어 있지 않은 매핑 파일을 요구합니다. localOnly/playCloud R8 패키지 검사 JSON에 APK·매핑 해시 및 확인 항목을 남깁니다. 일반 debug/baseline APK에는 적용하지 않습니다.
- 자세한 라이브러리별 판단 및 잔여 실제 기기 QA는 [GP-R8-03 호환성 검토](gp-r8-03-compatibility.md)를 참고합니다.

## GP-R8-04 최적화 APK 로컬 업그레이드 검증

기존 API36 기기 비교 후 동일 임시 키로 서명한 비최적화 APK를 설치하고, 합성 사진·동영상으로 목록 설정·보호·중복 분석 상태를 생성합니다. 앱 데이터를 지우지 않고 R8 APK를 덮어써 실제 Room DB/WorkManager 기록과 UI·미리보기·새 중복 분석/OCR 실행을 검증합니다. 측정과 회귀 보고서가 모두 있어야 검사 성공입니다. 이 검사는 같은 소스/버전의 최적화 전후 업데이트로, 이전 Play 게시 버전이나 운영 로그인/결제/백업의 검증을 대체하지 않습니다. [검증 범위 및 운영 QA](gp-r8-04-regression.md).

## GP-R8-05 DEX 최적화 측정

AAB의 실제 비압축 DEX 크기, R8 컴파일러가 내장한 통계 및 DEX SHA-256 일치 여부를 측정합니다. 각 프로필의 기존 `metrics.json`에 `aab.dex_optimization`과 `comparisons`를 추가합니다. 매핑의 클래스 개명 비율, 실제 파일/DEX 감소율은 Play 최적화 비율과 별개입니다. 메타데이터 누락·오류는 미확인 상태로 기록하며 실제 Play Console 판정은 아직 하지 않습니다. [측정 기준 및 사용 방법](gp-r8-05-dex-metrics.md).

## 기기 설치 공간과 시작 시간

[Android package benchmarks](https://github.com/hebi10/GalliPick/actions/workflows/gp-build-benchmark.yml)는 R8 빌드 설정 또는 측정 코드 변경 PR에서 이 API 36 비교를 자동 실행합니다. 일반 기능 PR의 전체 기기 QA와는 별개입니다. 수동 실행도 가능하며 이 경우 `measure_device`를 선택합니다. 별도 Android 36 에뮬레이터에 임시 테스트 키로 서명한 Play Cloud 기준 APK와 R8 APK를 차례로 설치합니다. 두 APK에서 홈, 파일 탭, 필터 열기·닫기를 검증합니다. 저장 공간과 시작 시간은 `play-cloud-device-metrics` 아티팩트의 `metrics.json`에 남습니다. AVD 생성 및 실행은 동일한 `ANDROID_USER_HOME`, `ANDROID_EMULATOR_HOME`, `ANDROID_AVD_HOME`을 사용하고, APK 빌드 전에 실제 AVD 등록 파일과 `emulator -list-avds` 결과를 검증합니다. 벤치마크 전용 Linux 에뮬레이터는 독립 프로세스 그룹에서 실행하고 종료를 제한 시간 내에 처리합니다. 측정이 실패하거나 종료에 실패하면 성공 처리하지 않습니다. 패키지 측정에서 Maven Central HTTP 429가 확인되면 새 임시 결과 경로로 1회 재시도하며, 다른 오류는 그대로 실패 처리합니다.

- 설치 코드와 첫 화면 검증 후 생긴 앱 데이터의 디스크 사용량을 `du -sk` 기준 KiB로 각각 기록합니다. 에뮬레이터에 직접 설치한 APK의 측정이며 Play 기기별 다운로드 크기나 Android 설정의 저장 공간 합계와는 집계 범위가 다릅니다.
- 첫 화면 검증으로 초기 설정을 마친 뒤, 프로세스를 종료한 cold start와 같은 프로세스에서 Activity를 다시 만든 warm start를 각 5회 측정합니다. Android가 표시한 시작 유형을 검사하고 `am start -W`의 첫 화면 표시 시간 `TotalTime` 원본과 중앙값을 기록합니다. 실제 기기 및 사진이 많은 환경의 시작 시간은 해당 환경에서 추가로 측정해야 합니다.
- 실험 빌드의 Room 데이터 읽기·갱신, OCR, 로컬 미디어 열기·복원은 추가 기기 검증이 필요합니다. Google 로그인·App Check, Billing 테스트 구매, AdMob 테스트 광고·UMP 동의, 클라우드 백업·복원은 서비스에 연결된 별도 테스트 서명과 환경에서 검증합니다. 기본 릴리스 축소 설정의 main 반영 및 배포는 이 회귀 검증까지 완료한 후 판단합니다.

[작업 이슈 #131](https://github.com/hebi10/GalliPick/issues/131) · [Android DEX 형식](https://source.android.com/docs/core/runtime/dex-format) · [Android 코드 및 리소스 최적화](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)

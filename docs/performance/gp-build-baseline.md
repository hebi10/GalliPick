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

`--r8`은 `releaseBenchmark` 전용 `gallipickMinifyBenchmark=true`를 지정합니다. R8 코드 축소와 리소스 축소가 함께 적용되며 Android Gradle Plugin의 `proguard-android-optimize.txt`와 라이브러리 소비자 규칙을 사용합니다. 앱 전용 keep 규칙은 재현된 오류와 `app/build/outputs/mapping/releaseBenchmark/` 분석에 근거해 추가합니다. 다음 프로필 빌드에서 이 디렉터리를 지우므로 분석에 사용할 매핑은 각 빌드 직후 복사합니다.

```sh
./gradlew -PgallipickBuildBenchmark=playCloud -PgallipickMinifyBenchmark=true :app:assembleReleaseBenchmark :app:bundleReleaseBenchmark
```

기본 서명 릴리스와 `playPreparation` 설정에는 이 실험 옵션이 적용되지 않습니다. 위 비교는 패키지 크기 측정까지 지원합니다. 설치 공간과 cold/warm 시작 시간은 동일 기기, OS, 테스트 데이터에서 별도로 기록합니다. 축소 빌드의 Room 데이터 읽기·갱신, Compose 화면과 OCR, 로컬 파일 열기 및 복원을 확인하고 Google 로그인·App Check, Billing 테스트 구매, AdMob 테스트 광고와 UMP 동의, 클라우드 백업·복원은 해당 테스트 서명 및 환경을 마련해 기기에서 검증합니다. 기본 릴리스 축소 판단에는 패키지 실측과 이 회귀 결과가 모두 필요합니다.

[작업 이슈 #131](https://github.com/hebi10/GalliPick/issues/131) · [Android DEX 형식](https://source.android.com/docs/core/runtime/dex-format) · [Android 코드 및 리소스 최적화](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)

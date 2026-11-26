# GP-R8-05 — DEX 코드 최적화 측정

## 정책 근거 (2026-11-26 확인)

Google Play는 2027년 2월부터 DEX 코드가 일정 크기 이상인 일반 앱에 축소·최적화·난독화 각각 25% 이상을 요구합니다. 일반 앱 10 MB, 게임 50 MB. 공식 기술 품질 문서는 10 MB 초과, Android vitals 상세 문서는 10 MB 이상이라고 표현하므로 로컬 검사는 보수적으로 10,000,000 byte 이상부터 판정합니다. 실제 Play 적용 여부와 표시 수치는 업로드한 정확한 AAB의 App Bundle 탐색기에서 확인합니다.

현재 GalliPick AGP 8.13.2를 유지합니다. Google 공식 Android vitals 문서는 최신 패치 AGP 8.10 이상에서 AAB에 내장된 r8.json 통계를 사용한다고 설명합니다. R8 컴파일러 원본 metadata의 stats.noObfuscationPercentage, stats.noOptimizationPercentage, stats.noShrinkingPercentage는 각 비활성 비율이므로 100에서 빼서 적용 가능 비율을 기록합니다. 이 값은 제거된 바이트나 실제로 변경된 클래스의 단순 비율이 아닙니다.

- [Play Console 기술 품질 요구사항](https://support.google.com/googleplay/android-developer/answer/17492799?hl=ko-KR)
- [Android vitals DEX code optimization](https://developer.android.com/google/play/vitals/code-optimization)
- [Android CLI analyze](https://developer.android.com/tools/agents/android-cli/commands/analyze)
- [AndroidX의 R8 metadata 분석 소스](https://android.googlesource.com/platform/frameworks/support/+/85a2e8fdbe87037dc810135c41b11d9782f4cfe9/development/aabReport/src/main/kotlin/androidx/aab/R8JsonFileInfo.kt)
- [R8 metadata 버전별 통계 안정성 검사 소스](https://r8.googlesource.com/r8.git/+/e15b9b866c76718caa6ea53fbc582e1d156b89e7/src/main/java/com/android/tools/r8/metadata/impl/R8BuildMetadataImpl.java)

## 검사 구현

| 항목 | 기준 |
| --- | --- |
| AAB SHA-256 | 분석 대상 원본 파일의 SHA-256 |
| DEX 비압축 크기 | ZIP 내부 .dex 항목의 비압축 크기 합계, 실제 모듈 classes*.dex와 기타 dex를 분리 |
| r8.json | BUNDLE-METADATA/com.android.tools/r8.json을 그대로 읽음. 중복/형식/범위/유효 버전 검사 |
| DEX 검증 | 컴파일러가 기록한 dexFiles의 SHA-256과 실제 AAB 모듈 DEX의 SHA-256 다중집합 일치 |
| 3개 비율 | 비활성 퍼센트를 100에서 차감하고 활성 옵션과 함께 개별 판정 |
| 매핑 보조 지표 | AAB 내 proguard.map 클래스 매핑 행 중 개명된 비율 및 매핑 SHA-256. Play 점수 대용 금지 |
| 실제 절감률 | 동일 프로필 baseline/R8 APK·AAB 크기, 비압축 모듈 DEX, 메서드 참조 수 차이. 각 0이 아닌 기준선 대비 계산 |

메타데이터가 없거나 불완전하거나 신뢰할 수 없는 버전/해시 불일치가 있으면 비율을 0% 또는 임의 추정치로 대체하지 않습니다. JSON의 local_threshold_assessment는 로컬 판정이며 실제 Play Console 상태는 항상 unverified입니다. 기타 dex가 있는 적용 대상 AAB도 별도 확인이 필요합니다. GP-R8-05는 진단 및 측정 도구이며 GP-R8-06에서는 같은 분석을 실제 서명 AAB 검증과 게시 직전 차단 조건으로 사용합니다. [릴리스 차단 정책](gp-r8-06-release-gate.md).

## 실행

기존 벤치마크는 baseline 및 R8 AAB를 각각 생성할 때 측정합니다. metrics.json의 aab.dex_optimization 및 comparisons를 확인합니다. APK/AAB 또는 매핑 원문을 새로 업로드하지 않고 요약 JSON과 기존 의존성 보고서만 보관합니다.

이미 보유한 AAB도 운영 키, 서비스 계정, 신규 라이브러리 없이 분석할 수 있습니다.

```sh
python3 scripts/performance/gp_dex_metrics.py path/to/app.aab --output build/gp-dex-report.json
```

공식 Android CLI가 설치된 환경에서는 독립적으로 교차 확인 가능합니다.

```sh
android analyze --analysis=optimization --format=json path/to/app.aab
```

정책 25%와 비교할 대상은 r8_metadata.scores_percent입니다. mapping_class_proxy 및 comparisons는 별도의 진단용 측정치이며 정책 비율이 아닙니다.

## 검증 및 후속 작업

- 정상/누락/위조·변경된 DEX/비정상 비율·중복 JSON/경계값/기타 DEX/버전 오류를 합성 아카이브 테스트로 검증.
- 실제 CI에서 localOnly, playCloud R8 AAB의 메타데이터와 수치 확인. devCloud는 비최적화 진단 기준선.
- 선행 GP-R8-01~04 및 이 단계의 최신 CI와 운영 연동 검증을 통과하기 전 main 반영하지 않음.
- GP-R8-06은 실제 릴리스 산출물 검사 및 3개 기준 미달/미확인 차단, 정확한 SHA 및 매핑 보존 정책.
- GP-R8-08의 승인된 내부 테스트 배포 후 App Bundle 탐색기에서 실제 게시 버전·3개 비율·DEX 크기·경고 해소 여부 확인.

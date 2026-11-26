# GP-R8-06 — 실제 릴리스 R8 차단 조건

## 적용 범위

- `verify_aab.py`는 기존 서명 인증서·번들 구조·매니페스트·환경·소스 검사를 통과한 실제 AAB 스냅샷에서 R8을 검사합니다. 실패하면 업로드용 AAB와 provenance를 생성하지 않습니다.
- OPS-04 `internal.py verify`는 기존 승인된 main SHA, 원본 서명 및 SHA-256 검증에 더해 원본 AAB의 R8 결과를 다시 계산하여 기존 provenance와 비교합니다. 실패 시 게시 전 단계에서 중단합니다.
- `play_publish.py`는 읽어 둔 동일한 불변 업로드 바이트에 대해 R8 결과를 다시 계산하고 original provenance와 대조한 후에만 Play edit를 시작합니다. check 모드의 읽기 확인은 그대로 유지합니다.
- 두 실제 signed-bundle build 경로는 Gradle R8 구성 검사도 실행합니다. 기존 서명, versionCode, 배포 승인/트랙/비공개 승격 조건은 바뀌지 않습니다.

## 차단 기준

| 조건 | 처리 |
| --- | --- |
| r8.json 부재·불완전·지원되지 않는 버전·DEX 해시 불일치 | 차단 |
| 난독화·최적화·축소 중 비활성 옵션 | 차단 |
| 모듈 이외 DEX 발견 | 자동 평가하지 않고 차단 |
| 비압축 DEX 총합 10,000,000 byte 이상에서 3개 비율 중 하나라도 25% 미달 | 차단 |
| 10 MB 미만 | 정책 크기 예외로 분류하되 GalliPick 내부 규칙으로 유효 R8 메타데이터와 3개 활성 옵션 및 내장 매핑은 여전히 필수 |
| 내장 proguard.map 누락·잘못된 인코딩·빈 클래스 매핑 | 차단 |
| AAB 바이트 또는 재계산 보고서와 원본 증빙 불일치 | 차단 |

실제 Play Console 값과 동일한지는 업로드 후 Android vitals/App Bundle 탐색기에서 확인해야 합니다. 이 검사는 로컬의 보수적인 배포 안전 조건입니다. 클래스 개명 비율과 실제 DEX 절감률을 Play 정책 수치로 대신 사용하지 않습니다.

## 증빙 보존

기존 아티팩트 파일 구조를 유지합니다. `provenance.json`에 `r8_optimization`(AAB SHA-256, 개별 DEX SHA-256, 3개 지표 및 매핑 SHA-256)을 저장하고 OPS-04의 검증 영수증에도 재계산 결과를 포함합니다. AAB 자체의 내장 매핑은 원본 상태로 유지합니다. 별도의 매핑 원문이나 키를 로그/공개 문서에 출력하지 않습니다.

현재 signed AAB Actions 아티팩트 보관 기간은 기존 14일입니다. 실제 릴리스 시 이 정확한 AAB와 provenance를 승인된 비공개 릴리스 보관소에 별도로 보존하여 향후 장애 분석/디옵퍼스케이션에 사용할 수 있게 해야 합니다. 본 작업은 장기 보관소 생성이나 운영 배포를 실행하지 않습니다.

## 검증

실제 릴리스·Play 업로드를 자동 CI에서 수행하지 않습니다. 합성 ZIP의 정상/미달/누락/해시 변조/증빙 불일치에 대한 단위 테스트, 기존 임시 키의 서명 검증 테스트, OPS-04의 차단 및 Play 모의 API의 네트워크 호출 전 차단을 확인합니다. 기존 벤치마크는 실제 최적화 시험 AAB를 사용해 지표를 별도로 측정합니다. GP-R8-07에서 선행 PR 최신 CI/호환성/운영 연동 검증을 통합 확인합니다.

공식 참고: [DEX code optimization](https://developer.android.com/google/play/vitals/code-optimization), [Google Play 기술 품질 요구사항](https://support.google.com/googleplay/android-developer/answer/17492799).

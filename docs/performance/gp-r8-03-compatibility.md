# GP-R8-03 — R8/ProGuard 호환성 검토

검토 기준: GP-R8-02 PR #192의 `1b09da82bd2beb9d5e41ffef159eb5268beeef3c`. 실제 출시나 운영 서비스 테스트 완료를 의미하지 않습니다.

## 확인한 앱 코드와 조치

| 구성 | 앱 소스의 사용 방식 | 규칙 및 검증 |
| --- | --- | --- |
| WorkManager 2.10.1 | MediaRepository: DuplicateScanWorker, PhotoAnalysisWorker, PhotoOcrWorker 일회 작업. CloudAutoBackupSettings: CloudAutoBackupWorker 주기 작업. Worker 클래스 이름은 WorkManager DB에 저장 | 4개 클래스 이름 및 공개 (Context, WorkerParameters) 생성자만 명시 보존. 최적화 APK DEX 검증. 기존 설치에서 예약한 작업의 업데이트 후 수행은 GP-R8-04 |
| Room 2.8.3 / KSP | MediaDatabase.get: Class 인자로 Room.databaseBuilder, gallipick-local.db, 기존 1→8 migration | Room 자체 consumer rules 사용, MediaDatabase/MediaDatabase_Impl 및 구현 생성자 DEX 검사. DB 업그레이드와 실데이터 보존은 GP-R8-04 |
| Firebase Auth 24.0.1 / App Check 19.0.2 / Credentials 1.3.0 / Google ID 1.1.1 | CloudAccountProvider: FirebaseOptions 및 직접 SDK API, 생성 리소스는 R.string 직접 참조. 빈 설정이면 초기화 전 종료 | SDK consumer rules 유지. 실제 로그인, 토큰 갱신·무효화, App Check, 계정 변경은 Play 서명/서비스 테스트 필요 |
| Billing 9.1.0 | PlayCloudBilling: BillingClient 및 상품/구매 콜백 직접 사용. 서버 응답은 JSONObject 명시 키 | 전체 SDK keep 추가하지 않음. 조회·구매·취소·복원·권한 전환은 테스트 상품/계정에서 별도 검증 |
| AdMob 1.6.0 / UMP 4.0.0 | GalliAds 및 GalliRewardedAnalysis: 광고/동의 SDK API 직접 호출 | SDK 규칙 사용. 공식 테스트 광고·동의·보상 처리 및 광고 제거는 서비스 테스트 필요 |
| ML Kit text recognition 16.0.1 / Korean 16.0.1 | PhotoOcrWorker: TextRecognition.getClient 및 한국어/라틴 recognizer | Worker 진입점 보존; SDK 자체 규칙 사용. 실제 합성 한글/영어 이미지 OCR 회귀 필요 |
| Compose / Activity / 리소스 | GalliPickApplication, MainActivity 매니페스트 등록, Compose/BrandAssets의 직접 참조 | AGP/SDK 자동 규칙 사용. 기존 홈·파일·필터 시험 외 다크 모드, 파일 열기, 권한·회전·복원 검증 필요 |
| JSON/설정/파일 | AppSettings의 JSON과 enum name, CloudBackupTransport의 명시 JSON 키, Room 엔티티에 명시 저장값 | 데이터 모델 전체 keep 불필요한 것으로 판단. 기존 설정/DB/서버 계약의 실제 호환성 검증 필요 |

앱 코드의 검색 및 관련 구현 검토에 근거한 판단입니다. 개별 SDK의 모든 전이 consumer rule 및 런타임 경로를 직접 검증했다고 주장하지 않습니다. WorkManager 소비자 규칙과 중복되더라도 앱 업데이트 간 ABI를 명시적으로 고정합니다. Room과 SDK의 광범위한 keep/dontwarn은 추가하지 않습니다.

## 자동 검사 완료 기준

1. GP-R8-02 빌드 설정 검증 통과.
2. localOnly 및 playCloud 최적화 APK에서 원래 이름의 4개 Worker와 생성자, Room 기반 및 생성 구현 클래스/생성자 확인. 이름 복원 매핑을 apkanalyzer에 넘기지 않음.
3. R8 mapping.txt 존재·비어 있지 않음, APK와 매핑의 SHA-256 기록. 실제 운영 mapping 보관/Play 최적화 점수는 후속 작업.
4. 잘못된 이름, 누락/다른 시그니처/비공개 생성자, 누락 매핑 또는 도구 실패 시 검사 실패.
5. 기존 API36 에뮬레이터 기본 동작 검사 및 Android·보안·OPS CI 성공. GP-R8-04까지 main 반영/배포 보류.

## 출처

- [WorkManager: Renaming and Removing ListenableWorker Classes](https://developer.android.com/reference/androidx/work/WorkManager)
- [R8 keep rules overview](https://developer.android.com/topic/performance/app-optimization/keep-rules-overview)
- [R8 keep rules examples](https://developer.android.com/topic/performance/app-optimization/keep-rule-examples)
- [Room constructor consumer rule change](https://android.googlesource.com/platform/frameworks/support/+/e4765e38e27a3f4d86188b996df97a52403dff46%5E%21/)
- [APK Analyzer CLI](https://developer.android.com/tools/apkanalyzer)

# CLOUD-02 원본 백업 시험 환경

`gallipick-dev`의 허용된 Google 계정으로 사진·동영상 한 파일을 백업합니다. 파일당 64 MiB, 계정당 1 GiB와 250건, 전체 2 GiB와 500건까지 예약합니다. 예약·검증 완료 원본의 합계에 모두 적용합니다. 같은 계정의 SHA-256·바이트 수가 같으면 이전 예약을 재사용합니다. 변경된 파일은 새로운 원본입니다.

## Firebase 연결

1. Firebase 프로젝트 `gallipick-dev`에서 Google Authentication과 Native Firestore 기본 데이터베이스를 확인합니다. 이 시험 환경의 Firestore 및 Storage 규칙은 모든 앱 직접 읽기·쓰기를 막습니다. 프로젝트를 명시하고 해당 프로젝트의 다른 서비스가 있다면 배포 전에 규칙 변경 범위를 확인합니다.
2. `gallipick-dev.firebasestorage.app` 버킷을 사용합니다. Cloud Storage에서 서울(`asia-northeast3`), Standard, 일관된 버킷 수준 액세스, 공개 액세스 방지 강제 적용을 확인합니다. 원본에 영향을 줄 수 있는 수명 주기 규칙이 없어야 요청이 승인됩니다.
3. 실행 계정 `gallipick-backup@gallipick-dev.iam.gserviceaccount.com`에 Firestore 문서 읽기·쓰기, 지정 버킷의 메타데이터 조회 및 객체 생성·읽기, 자기 서명 권한(`iam.serviceAccounts.signBlob`)을 부여합니다. 객체 삭제와 교체는 실행 계정에 부여하지 않습니다. 서비스 계정 JSON 키 파일은 필요하지 않습니다.
4. Firebase Functions 환경 변수 `GALLIPICK_BACKUP_BUCKET`에 정확한 버킷 이름, `GALLIPICK_BACKUP_TEST_UIDS`에 개발용 계정 UID 최대 10개를 쉼표로 구분해 설정합니다. SHA-1을 등록한 디버그 서명 설치본으로 테스트합니다. 실제 UID와 환경 설정 파일은 저장소에 올리지 않습니다.
5. 아래 검증을 마친 뒤 `GALLIPICK_BACKUP_UPLOADS_ENABLED=true`로 바꿉니다. 기본값에서는 백업 요청이 차단됩니다. 함수는 서울에 배치되며 두 인스턴스까지만 확장합니다. 요청은 시간당 계정별 120회로 제한합니다. 과금 예산 알림을 사용량 상한과 별도로 관리합니다.

의존성 설치 및 테스트 명령은 Node.js 22에서 실행합니다.

```powershell
npm --prefix firebase/functions ci
npm --prefix firebase/functions test
```

### 시험 계정과 버킷 설정

승인된 운영자가 테스트 프로젝트에 필요한 권한을 설정합니다. 아래 명령은 PowerShell 기준입니다. 기존 서비스 계정이나 IAM 역할이 이미 있다면 `create` 명령은 생략합니다.

```powershell
$PilotProject = 'gallipick-dev'
$PilotBucket = 'gs://gallipick-dev.firebasestorage.app'
$PilotServiceAccount = 'gallipick-backup@gallipick-dev.iam.gserviceaccount.com'
$PilotMember = "serviceAccount:$PilotServiceAccount"
gcloud services enable iamcredentials.googleapis.com --project $PilotProject
gcloud iam service-accounts create gallipick-backup --project $PilotProject
gcloud projects add-iam-policy-binding $PilotProject --member $PilotMember --role roles/datastore.user
gcloud iam roles create gallipickBackupBucketMetadata --project $PilotProject --title 'GalliPick backup bucket metadata' --permissions storage.buckets.get --stage GA
gcloud storage buckets update $PilotBucket --uniform-bucket-level-access --public-access-prevention --project $PilotProject
gcloud storage buckets add-iam-policy-binding $PilotBucket --member $PilotMember --role roles/storage.objectCreator --project $PilotProject
gcloud storage buckets add-iam-policy-binding $PilotBucket --member $PilotMember --role roles/storage.objectViewer --project $PilotProject
gcloud storage buckets add-iam-policy-binding $PilotBucket --member $PilotMember --role "projects/$PilotProject/roles/gallipickBackupBucketMetadata" --project $PilotProject
gcloud iam service-accounts add-iam-policy-binding $PilotServiceAccount --member $PilotMember --role roles/iam.serviceAccountTokenCreator --project $PilotProject
```

### Functions 실행 전 버킷 검증

운영자가 기존에 부여받은 해당 서비스 계정 가장 권한으로 실행 계정과 동일한 권한을 사용합니다. 앱 접근을 비활성화한 상태에서 `probe:gcs`를 실행하며, 사용자 원본 대신 합성 바이트를 고정 길이로 전송합니다. 일회성 `cloud02-preflight/` 경로는 앱 카탈로그와 분리되며, 검사에 표시된 경로의 합성 객체는 유효 기간이 지난 뒤 테스트 담당자가 정리할 수 있습니다.

```powershell
gcloud auth application-default login --impersonate-service-account $PilotServiceAccount
npm --prefix firebase/functions run probe:gcs
```

검증에서 `PASS`를 확인하고, 별도 승인된 테스트 배포의 경우 `firebase/functions/.env.gallipick-dev`에 위 환경 변수를 추가해 `--project`로 대상을 지정합니다. 처음에는 `GALLIPICK_BACKUP_UPLOADS_ENABLED=false`로 배포합니다. 두 함수와 두 규칙을 확인한 후 허용된 합성 파일 기기 검증을 시작할 때 플래그를 켜고 같은 프로젝트로 다시 배포합니다. 다른 Firebase 프로젝트나 Play 출시는 이 절차의 대상에서 제외됩니다.

```powershell
firebase deploy --only functions:cloud02,firestore:rules,storage --project gallipick-dev
```

## 합성 파일로 확인할 항목

| 확인 | 기대 결과 |
|---|---|
| 실제 버킷의 V4 서명 URL로 정상 길이 PUT | 요청한 바이트 수와 MIME으로 객체 1건 생성 |
| 예약 길이보다 1바이트 짧거나 긴 PUT | 객체 생성 전에 거부 |
| 이미 존재하는 같은 객체로 PUT 재시도 | 세대 조건 거부. 기존 세대의 원본은 변경 없이 완료 확인 가능 |
| 서명한 헤더 생략·변경 및 다른 경로 요청 | 서명 오류로 거부 |
| 요청 UID 변경·익명 요청·허용 목록 밖 계정 | 예약·완료 및 원본에 접근할 수 없음 |
| 같은 길이의 다른 내용으로 완료 요청 | SHA-256이 달라 카탈로그 게시 차단 |
| Firebase SDK 직접 읽기·쓰기와 익명 Cloud Storage 읽기 | 거부 |

Firebase callable `beginBackup`는 `{sha256,sizeBytes,mimeType}`를 받고, `finishBackup`은 `{uploadId}`를 받습니다. 업로드 전에는 계정별 고정 객체 키를 예약합니다. 서버가 정확히 그 객체 세대를 읽어 용량과 SHA-256을 확인하면 Firestore 카탈로그의 `VERIFIED`로 게시합니다. 검증된 세대는 같은 키에 새로 쓰지 않습니다. 서명 URL이 만료되면 같은 원본을 다시 시도해 같은 예약으로 이어갈 수 있습니다. 이 시험에서는 실패하거나 중단한 예약도 용량에 남겨, 늦은 전송과 용량 해제가 경합하지 않도록 합니다. 잘못된 콘텐츠로 객체가 만들어졌다면 운영 담당자가 만료·진행 중 전송 여부와 객체 상태를 확인한 후 정리합니다.

로컬 테스트는 예약, 계정 격리, 완료 재시도, 해시 불일치, 안전 설정을 확인합니다. 실제 Firebase 테스트 프로젝트에서 서명 권한·버킷 응답과 기기 전체 흐름을 검증한 뒤 실제 원본을 사용합니다. 시험용 원본의 다운로드, 기기 삭제, 구독과 운영 공개는 이후 단계입니다.

공식 참고 [Firebase callable](https://firebase.google.com/docs/functions/callable) · [Cloud Storage 서명 URL](https://cloud.google.com/storage/docs/access-control/signing-urls-with-helpers) · [Cloud Storage XML 헤더](https://cloud.google.com/storage/docs/xml-api/reference-headers) · [세대 조건](https://cloud.google.com/storage/docs/request-preconditions)

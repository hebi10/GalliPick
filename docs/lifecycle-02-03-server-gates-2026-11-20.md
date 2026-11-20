# GP-LIFECYCLE-02~03 — 운영 Cloud 만료 이력과 서버 접근 제한

기준: 2026-11-20. 관련 PR: https://github.com/hebi10/GalliPick/pull/147

## 범위

- 기존 GalliPick 운영/라이선스 구독 검증 기록을 보존하고, Play Developer API가 반환한 **유료 이용기간 종료 시각**으로 복원 기간을 계산한다. Google Play 자동 갱신 취소를 즉시 만료로 간주하지 않는다.
- 함수 서버의 일반 백업, 분할 업로드, 시크릿 클라우드 엔드포인트에 서버측 접근 권한을 검사한다.
- 직접 구매/복구/RTDN 재검증에서만 이력을 갱신하고, 클라이언트 파라미터로 만료일·권한을 부여하지 않는다.
- 실제 사진·영상 및 Firebase Auth 계정 삭제 작업, 자동 삭제 스케줄러, 사용자-facing 날짜별 UI, 관리자 웹은 이번 PR에 포함하지 않는다.

## 환경 분리 및 기능 스위치

`GALLIPICK_PRODUCTION_CLOUD_RETENTION_ENABLED=true`일 때만 **새 접근 정책**을 활성화한다. 기본은 꺼짐. 운영 `gallipick-production`에서 `GALLIPICK_PRODUCTION_CLOUD_ENABLED=true`로 별도 검증되어야 이 정책이 활성화된다. 개발 `gallipick-dev`의 기존 시험 결제 계약은 변경되지 않는다.

- 라이선스 구독 계정 이력: `gallipickProductionLicenseBillingAccounts/<hashedUid>`.
- 실제 구독 이력: `gallipickProductionBillingAccounts/<hashedUid>`.
- 각 계정의 `cloudLifecycle`은 서버 검증 상품 ID·basePlanId·익명 토큰 해시·최종 검증 만료시각·복원 허용 여부·조회시각만 저장한다. 구매 원문 토큰과 실제 사진은 기록하지 않는다.
- 기존에 확인되지 않은 구독의 과거 만료일은 만들어내지 않는다. 전환 전 기존 사용자 이력을 백필하거나 별도 절차를 확정해야 한다.

## 이용 권한

| 상태 | 업로드·마무리 | 기존 파일 열람/다운로드 | 표시 시각 |
| --- | --- | --- | --- |
| Play 서버 검증 활성 Cloud | 기존 요금제·용량·서버 설정 범위에서 허용 | 허용 | 실제 Play 만료 시각 |
| 만료 이후 0~7일 | 차단 | 허용 | 만료+7일 복원 종료 |
| 만료 이후 7~14일 | 차단 | 차단 | 만료+14일 삭제 예정 |
| 만료 이후 14일 이후 | 차단 | 차단 | PURGE_DUE(실제 삭제는 수행되지 않음) |
| 결제 상태 보류·무효/환불·이력 없음 | 차단 | 차단 | 임의 날짜 노출 없음 |
| 신규 서버 확인 구독 재개 | 현재 구독 기준 권한 재계산 | 허용 | 새 만료 시각 |
| 탈퇴 처리(향후 별도 API) | 즉시 차단 | 즉시 차단 | 삭제 예정 상태 |

서버에서 `cloudLifecycleState`, `cloudReadAllowed`, `cloudRecoveryEndsAtMs`, `cloudDeletionDueAtMs`을 조회용 계약으로 반환한다. 클라이언트가 새 필드를 읽고 적절한 안내를 표시하는 작업은 `GP-LIFECYCLE-04`에서 수행한다.

## 공통 엔드포인트

- 일반: `beginBackup`, `finishBackup`, `getBackupPart`는 쓰기, `listBackups`, `getBackupDownload`, `getBackupStatus`는 읽기.
- 시크릿: `setup`, `beginBackup`, `finishBackup`, `getBackupPart`는 쓰기, `status`, `unlock`, `listBackups`, `getBackupDownload`, `getBackupStatus`는 읽기.
- 모든 분류는 `cloud-api-access.js`에서 닫힌 목록으로 관리하고 목록에 없는 동작은 거부한다.
- 클라우드 업로드 할당량은 기존 Firestore transaction 내부에서 검증한다. 이어받기 백그라운드 검증/합성 작업은 **VERIFIED로 게시하기 직전** 현재 할당량을 재확인한다(기능 스위치 사용 시). 중간에 유료기간이 끝나면 보관함에 게시하지 않는다.
- 최초 signed URL이 이미 발급됐으면 해당 URL의 짧은 만료 전까지 접근이 남을 수 있다. 시크릿/일반 다운로드 URL의 유효기간을 포함하여 라이선스 시험 실기기에서 검증한다.

## 출시·롤백 전제

1. 운영 사용자별 마지막 Play 확인 시각, 만료일, 회수/환불 상태가 정확한지 검토. 기존에 원본을 보관하지만 서버 이력이 없는 계정은 잠금 대상이 될 수 있으므로 자동으로 기능을 켜지 않는다.
2. 라이선스 테스트 계정 두 개 이상으로 활성/취소/갱신/만료/복원/유예/보류/타 계정 구매 토큰 오용을 확인.
3. Cloud 원본을 여러 버전(v1 일반, v1 secret, v2 large, v2 secret)으로 준비하고 7일/14일 경계의 읽기·쓰기 권한을 확인.
4. 테스트에서 검증한 경우에만 프로젝트 운영 변수 활성화. 문제가 있으면 `...RETENTION_ENABLED`를 해제하여 기존 서버 접근 정책으로 롤백한다. 운영 Play/Functions 게시와 별도 사용자 승인을 지킨다.
5. 자동 삭제는 구현하지 않았으며, `PURGE_DUE` 상태만으로 사진·영상 삭제 완료를 주장하지 않는다.

## 확인 파일

- `firebase/functions/cloud-billing.js`: Play 검증 구매 이력 및 RTDN 지속, 서버 권한 검사
- `firebase/functions/cloud-retention-policy.js`: 활성/7일 복원/14일 예정/차단 판정
- `firebase/functions/cloud-api-access.js`: 모든 엔드포인트 분류
- `firebase/functions/index.js`: 일반/시크릿 서버 게이트
- `firebase/functions/resumable-backup.js`: 비동기 게시 직전 할당량 재검증
- 관련 `.test.js` 및 Cloud backend verification.

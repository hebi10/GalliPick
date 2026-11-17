# CLOUD-FILES-04 — 클라우드 동영상 바로 스트리밍

기준: `hebi10/GalliPick`, `CLOUD-FILES-03` 후속 기능  
구현 브랜치: `feature/cloud-files-04-video-streaming`

## 목표

기기에 원본 동영상을 복원하지 않고 GalliPick의 **파일 탭**과 **클라우드 보관함**에서 서버에 저장된 검증 원본을 바로 재생합니다.

## 동작

1. 선택된 클라우드 원본의 사진/동영상 MIME 형식과 파일 크기를 확인합니다.
2. 현재 로그인한 Google 계정으로 Firebase `getBackupDownload`를 호출합니다.
3. 응답의 객체 키·계정 소유권·세대(generation)·GET 서명을 기존 `validateBackupDownload`로 검증합니다.
4. 접근이 가능한 Google Cloud Storage 서명 URL에 **`Range: bytes=0-0`** 시험 GET을 실행합니다.
5. `206 Partial Content`, `Content-Range`, 바이트 길이, `x-goog-generation`, 인코딩을 검사합니다.
6. URL은 메모리에서만 전달하고 `VideoView`로 HTTP 바이트 범위 요청을 사용해 재생합니다.
7. 사용자가 재생·일시정지·구간 이동을 선택합니다. 버퍼링 시작/종료를 앱에서 표시합니다.
8. URL은 최대 약 120초로 발급되므로 일정 시간(90초)이 지나 사용자가 새 구간 이동·재생을 시도하면 **새 계정 인증 URL로 재연결**합니다. 네트워크 오류나 만료로 중단되면 재연결 버튼을 표시하고 이전 재생 위치를 유지합니다.
9. 앱을 백그라운드로 보내면 재생을 중지하며, 복귀 시 **자동 재생하지 않고** 사용자가 연결을 다시 시작합니다.
10. 영상 원본은 Android `MediaStore`/갤러리에 저장하지 않습니다. 사용자가 명시적으로 ‘기기에 복원’을 선택할 때만 기존 원본 복원 기능을 사용합니다.

## 보안·데이터 정책

- 서명 URL은 접근 토큰입니다. 로그, DB, 알림, 외부 Intent로 내보내지 않으며 코드에는 서명된 URL 문자열을 고정 저장하지 않습니다.
- 원본의 `SHA-256`은 업로드·서버 검증 단계에서 보장됩니다. **HTTP Range로 일부분만 재생할 때는 전체 바이트를 사전에 SHA-256 검증할 수 없습니다.** 재생은 객체 세대·길이·서명·Range 응답 확인을 기반으로 수행합니다.
- 사용자가 사진·동영상 열람 중 로그아웃 또는 계정 변경을 하면 파일 탭의 다이얼로그도 계정 전환에 맞춰 해제해야 합니다. 기존 `remember(uid)` 키 및 클라우드 카탈로그 소유권 검사를 유지합니다.
- Android 시스템 플레이어가 지원하지 않는 영상 코덱은 재생 오류를 표시합니다. 클라우드 인코딩/트랜스코딩은 이번 범위가 아닙니다.
- signed URL의 재발급은 변경·복구의 일부이며 완전한 무중단 장시간 재생은 실제 동영상/네트워크 환경 계측으로 검증해야 합니다.

## 변경 범위

- `CloudBackup.kt`: 계정별 영상 서명 URL 단기 발급
- `CloudBackupTransport.kt`: 객체 세대 확인·부분 GET 검증
- `CloudVideoStreamPolicy.kt`: 지원 미디어·만료·탐색 위치·Range 응답 검증
- `CloudVideoViewer.kt`: 앱 안 플레이어, 구간 이동, 버퍼링, 오류 재연결, 생명주기
- `FilesScreen.kt`: 클라우드 전용 영상 선택 시 직접 보기
- `CloudLibrarySection.kt`: 클라우드 보관함에서 직접 재생
- 단위 테스트: MIME/용량, 재생 위치, signed URL 비노출, Range 무결성·세대 불일치 처리

기존 로컬 미디어 휴지통·보호·중복 정리, 사진 뷰어, Firebase Functions, Google Play Billing/광고는 변경하지 않습니다.

## 완료 게이트

- [ ] JVM 테스트·Lint·Android 디버그 빌드
- [ ] API 36 Android 계측 테스트
- [ ] 실제 GCS 원본의 바이트 범위 요청 및 시크 재생 확인
- [ ] 앱 백그라운드·회전·네트워크 차단·로그아웃·토큰 만료 검증
- [ ] PR #106 → PR #107 선행 검증 및 안전 병합 후 본 PR 병합
- [ ] 내부 테스트 빌드로 실제 사용자 시나리오 점검

이 문서는 기능 구현 계획 및 작업 기록입니다. 운영 Firebase나 Google Play 트랙을 배포하지 않습니다.

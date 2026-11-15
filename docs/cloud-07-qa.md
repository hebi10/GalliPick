# CLOUD-07 통합 검증

검증 대상은 GalliPick 디버그 앱과 개발 Firebase 프로젝트 `gallipick-dev`입니다. 테스트 계정 두 개와 합성 사진·동영상을 사용하고, 입력과 복원 파일의 SHA-256 및 바이트 수를 기록합니다. 테스트 로그에서 계정 식별자, 인증 토큰 및 서명 URL을 제거합니다.

## 자동 검사

```bash
npm --prefix firebase/functions ci
npm --prefix firebase/functions test
gradle -PgallipickTestSigning=true :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:compilePlayPreparationKotlin
python3 -m unittest discover -s scripts/release -p 'test_*.py'
```

Cloud backend verification은 Node.js 22에서 서버 회귀 테스트를 실행합니다. Android verification과 Android device tests에서 앱 빌드, 로컬 저장소 기능, 클라우드 탭 진입을 확인합니다. 디버그 Firebase 설정이 빠진 빌드의 검사 결과는 개발 Firebase에 연결된 기기 검증 증빙과 구분합니다.

## 개발 환경과 기기 검증

| 검증 | 확인할 결과 |
|---|---|
| Google 로그인·두 계정 전환·로그아웃 | 재실행 시 현재 계정이 복구되며 이전 계정의 목록·전송·복원 상태가 노출되지 않음 |
| 원본 백업·전송 재시도 | 서버가 크기·SHA-256·스토리지 세대를 비교한 후 표시. 동일 계정의 같은 원본은 한 번만 보관 |
| 권한과 버킷 격리 | 직접 읽기·쓰기, 계정 간 원본 접근, 서명 조건 위반과 덮어쓰기 요청이 거부됨 |
| 새 기기 복원 | 같은 계정이 보관함을 열고 다운로드. MediaStore에 저장된 원본 및 EXIF를 다시 읽어 비교 |
| 복원 중단 | 네트워크 단절, 저장공간 부족, 취소, 계정 전환, SHA-256 불일치에서 미완료 항목이 갤러리에 공개되지 않음 |
| Play 라이선스 테스트 | 구매·복구, 갱신, 취소, 보류, 만료, 환불, 중복·역순 알림. 새 백업 허용과 기존 원본 복원 권한을 각각 확인 |
| 목표 용량 추천 | 보호·즐겨찾기·연관 RAW 제외. 추정치와 검증 사본 용량을 구분하고 영구 제거는 CLOUD-03 복원 검증과 Android 승인 안전성이 확보된 후 검증 |
| 자동 백업 | 선택 폴더만 처리. Wi-Fi·충전 조건, 취소·프로세스 종료·권한 철회, 두 기기에서 동일 원본의 중복 방지 확인 |
| 화면·기기 | API 30·34·35·36, 삼성 실기기·폴더블, 라이트·다크, 글꼴 160%에서 오류·빈 상태·접근성 확인 |

4K 장편 영상의 이어받기, 클라우드 계정 삭제·데이터 내보내기, 기기 영구 제거의 안전 검증은 해당 흐름이 구현된 빌드에서 별도로 수행합니다. 실기기 검증에는 개발 Firebase 설정, 등록된 디버그 인증서와 허용된 테스트 계정이 필요합니다.

## 개발 프로젝트 확인 결과

2026-11-15 KST에 `gallipick-dev` 서울 리전에서 `beginBackup`, `finishBackup`, `listBackups`, `getBackupDownload`의 활성 상태와 미인증 요청의 HTTP 401 응답을 확인했습니다. 앱 전송은 꺼진 상태로 배포했습니다. [Firebase Functions](https://console.firebase.google.com/project/gallipick-dev/functions)

같은 실행 계정으로 Cloud Run 검증 작업을 실행해 합성 파일의 정상 길이 업로드, 서명 헤더 누락 및 길이 위반 거부, 덮어쓰기 거부, 세대 고정 다운로드의 크기·SHA-256 일치, 익명 읽기 차단을 확인했습니다. [Cloud Run 검증 실행](https://console.cloud.google.com/run/jobs/executions/details/asia-northeast3/gallipick-backup-preflight-kzm6b?project=gallipick-dev)

# Play Console 등록 준비 기록

이 문서는 현재 소스에 근거한 선언 준비본입니다. 실제 콘솔 저장·앱 생성·심사 제출·공개 완료는 각각 주 작업에서 확인 후 기록합니다.

## 사용자 승인과 지원 정보

- 사용자가 GalliPick의 개발자 정책 선언과 미국 수출 관련 동의 진행을 승인했습니다. 콘솔의 실제 체크·저장 상태는 별도 확인 대상입니다.
- 개발자 표시는 hebi, 지원·개인정보 문의 이메일은 asdlkj0104@gmail.com입니다. 이 이메일의 공개 사용도 승인받았습니다.
- 개인정보처리방침 원본은 docs/privacy.html입니다. 예정 공개 URL은 https://hebi10.github.io/GalliPick/privacy.html 입니다.
- 공개 URL 접근과 앱 내 개인정보처리방침 연결은 준비된 HTML 파일과 별도로 확인해야 합니다.

## 데이터 보안 선언 근거

현재 release 소스와 병합 manifest 기준으로 앱의 자동 데이터 수집·제3자 공유는 없음으로 준비합니다. Google Play의 수집 정의인 기기 밖 전송과 기기 안에서만 처리하는 접근을 구분합니다.

| 항목 | 현재 확인 내용 |
| --- | --- |
| 기기 밖 데이터 수집 | 외부 전송 코드·서버 업로드 기능·INTERNET 권한 없음 |
| 제3자 공유 | 자동 공유 없음. 사용자가 선택하는 외부 앱 열기는 명시적인 사용자 동작으로 정책에 설명 |
| 광고·광고 ID | 광고 SDK와 AD_ID 권한 없음 |
| 계정 | 앱 내 가입·로그인 기능 없음 |
| 현재 위치 | GPS 조회 및 FINE/COARSE/BACKGROUND_LOCATION 권한 없음 |
| 사진 위치 메타데이터 | ACCESS_MEDIA_LOCATION으로 원본 바이트의 정확한 중복 검사에 사용 |
| 로컬 저장 | Room의 미디어 메타데이터·해시·보호·검사 상태, SharedPreferences의 검색 조건·테마 |
| 백업 | allowBackup=false 및 cloud-backup·device-transfer 제외 규칙 확인 |

전송 암호화 서비스나 독립 보안 심사를 완료했다고 선언할 근거는 없습니다. 수집하지 않는 앱에 해당하는 실제 콘솔 문항에 따라 입력합니다. 이메일로 직접 접수한 문의는 앱의 자동 전송 기능과 구분하고 개인정보처리방침의 문의 절차를 따릅니다.

## 사진·동영상 접근 선언 문구

GalliPick의 핵심 기능은 사용자가 허용한 기기 미디어 목록을 반복 조회하고, 파일 용량·영상 길이·날짜·폴더 등의 조건으로 검색하며, 파일 전체 내용을 비교해 동일한 사본을 정리하는 것입니다. 이를 위해 READ_MEDIA_IMAGES와 READ_MEDIA_VIDEO 접근이 필요합니다. 개별 파일 선택만으로는 허용된 미디어 전체의 목록 조회와 사본 관계 분석을 제공하기 어렵습니다. Android 14 이상에서 일부 파일만 허용한 경우에는 그 범위에서 기능을 제공합니다.

ACCESS_MEDIA_LOCATION은 사진에 포함된 위치 메타데이터를 포함한 원본 바이트를 읽어 정확한 SHA-256 중복 비교를 하기 위한 권한입니다. 현재 위치를 추적하는 권한이나 기능을 사용하지 않습니다. 원본을 읽을 수 없는 파일은 정확한 중복 검사에서 제외합니다.

Android 12 이하에는 READ_EXTERNAL_STORAGE를 사용하고 Android 13 이상에는 사진·동영상 권한을 구분합니다. READ_MEDIA_VISUAL_USER_SELECTED는 일부 파일 허용 범위를 반영합니다. WorkManager가 병합한 WAKE_LOCK, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE, ACCESS_NETWORK_STATE는 로컬 분석 작업 지원용이며 INTERNET 권한과 구분합니다.

권한 심사의 승인 여부는 Google Play 검토에 따릅니다. 심사 양식이 시연 자료를 요구하면 허용 범위의 파일 목록·정렬·중복 후보·최종 확인과 Android 승인 흐름을 실제 앱으로 보여 줍니다.

## 그래픽 준비 상태

- 기능 그래픽: assets/play-store/ko-KR/feature-graphic.png, 1024×500 RGB.
- 스크린샷 4개: 1350×2400 RGB. 실제 UI를 유지한 패딩으로 최대 2:1 비율 조건을 맞췄습니다.
- 앱 아이콘: 512×512. 업로드 전 32-bit PNG/RGBA 형식과 최종 파일을 확인합니다.
- 태블릿 전용 캡처와 권한 선언용 동영상은 별도 준비 여부를 확인합니다.

## 실제 저장 상태 기록란

- 앱 생성 및 동의 저장: 주 작업에서 확인 후 기록
- 스토어 문구·지원 연락처 저장: 주 작업에서 확인 후 기록
- 공개 개인정보처리방침 URL 접근: 주 작업에서 확인 후 기록
- 앱 안의 방침 연결 및 최종 업로드 빌드: 주 작업에서 확인 후 기록
- 데이터 보안·사진/동영상 권한 선언 저장: 주 작업에서 확인 후 기록
- 심사 제출·공개: 주 작업에서 확인 후 기록

## 공식 참고

- 데이터 보안: https://support.google.com/googleplay/android-developer/answer/10787469
- 사용자 데이터·개인정보처리방침: https://support.google.com/googleplay/android-developer/answer/10144311
- 사진·동영상 권한: https://support.google.com/googleplay/android-developer/answer/16558241
- 스토어 그래픽: https://support.google.com/googleplay/android-developer/answer/9866151
- 스토어 문구 길이: https://support.google.com/googleplay/android-developer/answer/9859152

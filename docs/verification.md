# 검증 기록

## v0.1.6 · 중복 정리 역할·확인 흐름

검증일: 2026-11-02. 소스의 릴리스 서명 기준을 사용자 제공 JKS로 전환했습니다. 공개 인증서 SHA-256은 `3da9245ec543bba6348541e60346ebe8ed296bc0fb09eea4ae763138204d1bb1`입니다. 새 키의 로컬 APK 검증을 완료했고 **Actions secret 갱신은 GitHub의 추가 본인 확인 대기이며 새 기준의 원격 CI 결과·공개 게시도 대기 중입니다.** 아래 전환 전 개발 키의 기능 검증 기록과 현재 JKS APK 검증을 구분하며 이전 결과를 새 CI 통과로 계산하지 않습니다.

### 변경 범위

- 중복 묶음의 파일을 `남길 파일`, `정리 후보`, `이동 대상 · 선택됨`으로 구분합니다. 남길 파일에는 직접 지정·보호·즐겨찾기·앱 추천의 이유를 표시하며 정리 선택 상자를 숨깁니다.
- 현재 결과 전체와 묶음별 선택을 구분하고, 버튼에 후보 개수를 표시합니다. 한 번에 최대 500개이며 선택만으로 파일을 이동하지 않습니다. 남길 파일 변경은 해당 묶음의 후보 선택만 해제합니다.
- 최종 확인창에서 이동 대상과 묶음별로 확인한 남길 사본을 탭으로 검토합니다. 확인 이후 파일이나 남길 사본이 달라지면 다시 검증하며, 실제 이동에는 Android 승인이 필요합니다.
- 승인 후 실제 휴지통 상태를 조회해 완료·변경 없음·확인 불가를 구분합니다. 확인이 어려운 파일을 완료 수에 포함하지 않습니다. 표시하는 용량은 파일 크기의 합계이며 즉시 확보된 저장 공간을 의미하지 않습니다.
- 다크 모드 저장, 한 줄 파일 도구와 14가지 정렬, 스크롤 시 상단 접힘, 창 크기에 맞춘 파일 상세·A/B 비교를 포함합니다. 비교 영상은 한 번에 하나만 재생합니다.
- 일반 패키지 `com.hebi.gallipick`, 이름 `GalliPick`, versionCode 7/versionName 0.1.6을 사용합니다. 현재 서명 기준은 사용자 제공 JKS이며 `.preview` 구분은 없습니다.

### 전환 전 개발 키의 로컬·기능 검증 이력

[PR #2](https://github.com/hebi10/GalliPick/pull/2)의 기능 검증 head는 `62d5543a4744e9e27412e124cf9d422aca9e1400`, Git 트리는 `81eb568ac626ba6ea52a606050c2796751d42eff`입니다. 병합 커밋 `3725e8d6ae67a38ed4a422eb7382c5c9c57b0ce0`의 트리도 같음을 확인했습니다. 이 단계는 기존 개발 키 기준이었습니다.

- 안내 메시지 갱신 보완 후 최종 로컬 빌드가 34초에 성공했습니다. JVM 보고서 XML 기준 **82개 통과**, 실패·오류·건너뜀 0개.
- debug lint: 오류 0개, 경고 17개·hint 1개. release lint: 오류 0개, 경고 15개·hint 1개.
- release 앱 APK와 Android 테스트 APK 빌드 성공. 작은 창 5개 조건의 실제 영상 표시 영역, 재검증 중 비교창 닫기, 최종 확인 뒤 파일 변경, SHA 증명 소실 검사를 포함해 컴파일했습니다. Android 실행 결과와 구분합니다.
- 릴리스 보조 스크립트의 Python 검사 18개 통과. 이는 Android 기기 실행 검사가 아닙니다.
- Android 실행 결과는 아래에 별도로 기록합니다. 휴대폰·태블릿 실기기 검사는 수행하지 않았습니다.

### 전환 전 Android 16/API 36 실행 이력

[첫 전체 실행](https://github.com/hebi10/GalliPick/actions/runs/41385029984)은 46개 중 43개 통과·3개 실패, [두 번째 실행](https://github.com/hebi10/GalliPick/actions/runs/41385906101)은 45개 통과·1개 실패였습니다. 초기에는 UI 선택자·캐시 초기화 대기·전경 판정 기준을 테스트 코드에서 보완했습니다. 추가 진단에서 최종 재검증의 구체적인 차단 안내가 새로고침 중 `AppViewModel.publishMedia`의 일반 선택 해제 안내로 덮이는 제품 문제를 확인했습니다. 실패 시점 스크린샷에는 Quickstep(`com.android.launcher3`) 응답 없음 화면도 있었습니다. 상세 경과는 [작업 기록](superpowers/plans/2026-11-02-duplicate-clarity-progress.md)에 남깁니다.

`MainActivity`에서 최종 재검증이 실패한 이동 요청의 선택을 구체적인 안내 설정·새로고침 전에 해제하도록 보완했고, 독립 안전성 검토를 거쳤습니다. 테스트는 변경한 파일명이 반영된 새 카탈로그·조회 완료·선택 해제를 기다린 뒤 차단 문구, 파일의 휴지통 미이동 상태, Activity 생명주기를 함께 확인하며 진단 자료를 유지합니다. CI는 `--no-daemon` 빌드를 에뮬레이터 부팅 전에 마치고 Launcher만 종료하도록 환경을 보완했습니다.

[최종 API 36 실행](https://github.com/hebi10/GalliPick/actions/runs/41389091080)은 XML 기준 **46개 통과, 실패·오류·건너뜀 0개**, 104.602초입니다. 실제 시스템 캡처에서 정확한 차단 안내가 표시되고 Quickstep 응답 없음 창이 사라진 것을 확인했습니다. 진단은 `ON_PAUSE=0`, `RESUMED`, 활성 창 패키지 `com.hebi.gallipick`이며 일반 선택 해제 안내는 표시되지 않았습니다.

[최종 빌드·단위 검사·lint 실행](https://github.com/hebi10/GalliPick/actions/runs/41389091073)도 성공했습니다. XML 기준 JVM **82개 통과, 실패·오류·건너뜀 0개**이며 Python 18개가 통과했습니다. CI debug lint는 오류 0개·경고 27개·hint 1개로 로컬 경고 수와 구분합니다. 두 실행 모두 위의 정확한 head를 검증했습니다.

### 사용자 제공 JKS의 현재 로컬 검증

제공 JKS의 무결성·개인 키 사용 가능 여부·서명 챌린지를 비공개로 확인했습니다. 이어 새 고정 지문으로 서명 검증·JVM 검사·debug/release lint·release 및 Android 테스트 APK 빌드가 12초에 성공했습니다. JVM 82개가 통과했고 lint 오류는 0개입니다. JKS 지원을 포함한 릴리스 보조 도구의 Python 검사 25개도 통과했습니다. 복구 ZIP의 키스토어·설정 바이트 보존을 확인했으며 실제 비밀번호나 별칭은 기록하지 않습니다.

현재 로컬 JKS APK는 46,602,758 bytes, SHA-256 `0c2c164b5bd9ffa0b5e9a5a8fe2a093ac4ad49107fafac275bda69d43fcdee6c`입니다. 패키지·버전·non-debuggable 설정과 공개 인증서 지문을 확인했습니다. 이 서명 기준의 새 CI·공개 릴리스 실행 결과는 아직 없습니다.

### 서명 전환과 공개 게시

이전 개발 키로 서명한 v0.1.5 및 이전 v0.1.6 시험 설치본은 현재 JKS와 서명이 다르므로 새 APK 공개 후 한 번 삭제·재설치해야 합니다. 로컬 필터·보호·분석 설정은 초기화될 수 있습니다. 앱 데이터 이전이나 서명 키 회전 증명은 수행하지 않았으며, 이후에는 같은 JKS 키·패키지와 더 높은 versionCode를 유지합니다.

기존 개발 키의 공개 릴리스 시도 [41392108200](https://github.com/hebi10/GalliPick/actions/runs/41392108200)는 main `4b274885c175bc91afc821a197a1c0dc1a21c674`에서 시작했으나 사용자 JKS 제공 후 게시 job 전에 취소했습니다. 이 실행으로 태그·초안·공개 릴리스는 생성되지 않았습니다. 새 JKS 복구 자료의 Actions secret 갱신을 요청했으며 GitHub의 추가 본인 확인 창에서 대기 중입니다. 아직 서버에 갱신이 저장됐다고 주장하지 않습니다. 개인 키와 비밀번호는 공개 소스·로그·릴리스에 넣지 않습니다.

첫 공개 예정 파일은 `GalliPick-v0.1.6-release.apk`와 `SHA256SUMS`입니다. 전환 전 개발 키의 로컬 APK도 이력으로 보존합니다. 크기는 46,602,758 bytes, SHA-256은 `fc19c2a1038e9f03ce9ce3bf42d354fc928ed7a33c6b0adc3c3cd72273264eb2`였으며 v0.1.5 개발 키와 같은 인증서였습니다. 이 과거 파일과 현재 JKS 파일을 구분합니다. 새 CI APK의 최종 크기·SHA-256·소스 커밋·Actions URL·공개 릴리스 URL은 검증·게시 완료 후 기록합니다. [최신 릴리스 경로](https://github.com/hebi10/GalliPick/releases/latest)는 실제 게시 상태에 따라 달라집니다.

아래 v0.1.2 이하 기록은 보존한 과거 이력입니다. 이번 v0.1.6의 최종 검증 및 공개 완료를 뜻하지 않습니다.

---

## v0.1.2 · 사용성·안전성 보완

검증일: 2026-11-01. 로컬 검증 커밋: `1bd89d8cbaa4c237eca0a9e7bd4b6767043faa42`. GitHub 게시·CI 검증 코드: `596dc27f339e6a0bf63c63b70159c9c05521f8ee`.

Android 11 이상 / compile·target SDK 36 / versionCode 3.

- 필터 변경과 현재 검색 범위의 선택을 분리해 화면 밖 파일이 휴지통 요청에 들어가지 않도록 보완했습니다. 선택 당시 fingerprint와 MediaStore 볼륨 버전을 유지하며, 복귀 시 현재 권한으로 확인된 동일 파일만 선택을 복원합니다.
- 파일·중복·휴지통의 선택을 500개로 제한하고 선택 단계에서 안내합니다. 확인창에서 파일명·폴더를 검토하며 시스템 승인 및 마지막 사본 검사를 유지합니다.
- 볼륨별 `getVersion()`과 해시의 epoch를 함께 저장합니다. DB v1→v2 마이그레이션을 추가했으며 보호 설정을 지우는 destructive fallback은 사용하지 않습니다.
- 조회 오류는 빈 성공과 구별합니다. 실패 시 화면을 숨기고 재시도를 제공하며 이전 체크포인트를 유지합니다.
- Room 카탈로그를 공유하고 중복 계산의 갱신을 250ms 간격으로 합칩니다. 메타데이터와 중복 결과가 같은 스냅샷일 때만 화면에 게시합니다. 생존 사본 검증은 필요한 해시 행을 인덱스로 조회합니다.
- 빠른 필터, 보관 파일 직접 지정, 접근 허용/조회 재시도, 선택이 없는 삭제 패널 접기와 목록 위치 유지, 검색·진행률 접근성 의미를 추가했습니다.
- 이미지 미리보기는 긴 변 최대 2,048px의 소프트웨어 sRGB 비트맵을 비동기로 디코딩하며 확대·이동·맞춤을 제공합니다. 동영상은 native VideoView 재생·일시정지·탐색을 사용하고 백그라운드·파일 변경·닫기 시 정리합니다. 외부 원본 열기도 유지합니다.
- 제품 의존성과 INTERNET 권한은 추가하지 않았습니다.

### 빌드와 회귀

- JVM 테스트 59개, 실패/오류/건너뜀 0개.
- lint 오류 0개. 권장 사항 경고 14개와 hint 1개가 남아 있습니다.
- debug 앱과 Android 테스트 APK 빌드 성공.
- 독립 코드 검토에서 확인한 확인창 개인정보 잔류, 연속 새로고침의 loading 소유권, 오래된 볼륨 버전 선택/미리보기, 중복 결과 게시 시점의 경합을 보완했습니다. 최종 검토에 Critical/Important 잔여 지적은 없습니다.

### GitHub 반영과 CI

[PR #1](https://github.com/hebi10/GalliPick/pull/1)을 통해 main에 반영했습니다. 게시한 Git 트리 `3525274ac9c868e4775e16931b76a14956eac1c1`이 로컬 검증본과 일치함을 확인했습니다.

- [빌드·단위 검사·lint](https://github.com/hebi10/GalliPick/actions/runs/41231627571): JVM 59개 통과, 실패/건너뜀 0개, lint 오류 0개, 앱 APK 빌드 성공. CI에서는 경고 26개와 hint 1개이며 위 로컬 경고 수와 구분합니다.
- [Android 16/API 36 기기 검사](https://github.com/hebi10/GalliPick/actions/runs/41231627558): 22개 통과, 실패/오류/건너뜀 0개. 보고서 XML 기준 34.578초입니다. KVM 환경의 AndroidJUnitRunner 전체 검사입니다.
- GitHub Actions의 APK는 개발 검증용 debug 빌드이며, 아래 전달 파일의 서명 검증 기록과 구분합니다.

### Android 실행 검사

최종 Android 11/API 30 실행 검사 **22개 통과, 실패/오류/건너뜀 0개**입니다.

Gradle/UTP의 별도 JVM에서 기기 정보 조회가 시간 초과되어 테스트 시작 전에 중단된 실행은 제품 검사 결과에 포함하지 않았습니다. 최종 검사는 동일한 앱·테스트 APK를 직접 설치하고, 클래스 필터 없이 `AndroidJUnitRunner` 전체를 실행했습니다. 원본 프로토콜의 START 22개·종료 성공 22개, `OK (22 tests)`, 최종 instrumentation 결과 -1을 각각 확인했으며 파생 XML의 수치와도 일치합니다.

첫 전체 검사에서는 22개 중 21개가 통과했습니다. 사진 복귀 검사 1개는 테스트의 가짜 LifecycleOwner가 Compose Dialog의 실제 Activity 소유자에 의해 덮어써져 실패했습니다. 실제 ActivityScenario의 stop/resume 및 같은 URI 이미지 교체 후 새 해상도 확인으로 검사 코드를 수정했습니다. 수정 후 실제 Activity 복귀 검사를 포함한 전체 22개를 다시 실행하여 모두 통과했습니다.

테스트는 실제 MediaStore PNG·WorkManager 검사와 생존 사본 확인, 보호/권한 표시 차단, 필터 변경·복귀·변경된 선택, DB 트랜잭션/볼륨 버전, 채워진 UI와 500개 제한·보관본, JPEG 디코딩·방향·복귀, 실제 합성 H.264 영상의 재생/일시정지 및 native 뷰 정리를 포함합니다. H.264 fixture는 테스트 전용 무음 4초, 160×90 baseline 영상입니다.

실제 캡처의 파일 목록·중복 보관본·휴지통 500개 선택 화면을 확인했습니다. 테스트용 목록 메타데이터를 사용한 캡처와 실제 파일 디코딩/재생 검사는 별개입니다.

### 전달 APK

`GalliPick-v0.1.2-debug.apk` · 63,630,083 bytes. 서명 검증 성공. 전달한 v0.1.1 APK와 인증서가 일치하여 기존 설치 위에 업데이트할 수 있습니다. 로컬 Android 11 기기 검사에 사용한 최종 APK와 전달 파일의 SHA-256도 일치합니다.

```text
SHA-256: ea3f0a201e71c393da4ac53ce19253ba152e4b69a317a381f70256bc3e7a89f0
```

별도 SQLite 연결에서 생성된 v1 스키마와 실제 v1→v2 마이그레이션 SQL을 실행해 기존 해시·보호 행 보존과 새 epoch 스키마 생성을 확인했습니다. 실제 이전 사용자 DB를 가진 폰의 전체 업그레이드 검증과 구분합니다.

### 확인 범위

로컬 Android 16/API 36 소프트웨어 에뮬레이터는 하드웨어 가속이 없는 환경에서 제한 시간 내 부팅되지 않았습니다. 이후 GitHub KVM 환경에서 API 36의 전체 22개 검사가 통과했습니다. 확인한 자동 검사 범위는 로컬 Android 11/API 30과 GitHub Android 16/API 36이며, 로컬 부팅 실패를 통과로 계산하지 않았습니다. 실제 사용자 폰의 시스템 승인/취소·복원, Android 14+ 일부 파일 허용 선택창, 대량 미디어/SD 카드/폴더블·큰 글꼴은 추가 확인이 필요합니다. 유사 사진·유사 동영상 탐지는 후속 범위입니다.

---

## v0.1.1 · 말랑한 추억 정리함

검증 소스: `728670f802925b70ae5eb86099b4d709220b79ef`, 2026-10-31.

- 이미지 생성 기본 도구로 선택된 비트맵 12종 제작. 컬러 런처/마스코트, 탐색 5종, 사진·영상·휴지통·보호·폴더, 단색 런처용 이미지입니다.
- 생성 원본과 프롬프트: `assets/brand/source/`, `assets/brand/generation-manifest.json`. 원본은 보존하고 Android 사본만 투명 여백 정렬과 축소를 수행했습니다.
- 공통 테마와 전체 UI에 크림·민트·복숭아 팔레트, 둥근 카드·입력·버튼·탭 적용. 작은 조작은 Material Rounded, 사진·영상 등 주요 의미는 생성 PNG로 표시합니다.
- JVM 회귀 38개 통과, Android lint 오류 0개, 앱/기기용 테스트 APK 빌드 성공.
- [GitHub 빌드·단위 검사·lint 통과](https://github.com/hebi10/GalliPick/actions/runs/41206003230).
- [Android 16/API 36 통합 검사 4개 통과](https://github.com/hebi10/GalliPick/actions/runs/41206003296). 실패/오류 0개.
- 실제 캡처 8개 확인: 홈·파일·필터·중복·정리·설정, 컬러 런처·단색 테마 런처. 각 화면의 여백·정렬·본문·스크롤·고정 조작과 이미지 잘림을 확인했습니다. 실기기 폴더블과 실제 미디어가 채워진 모든 상태의 시각 검증은 별도입니다.
- 본문 보조색 대비: 크림 4.95:1, Panel 4.67:1. 민트/복숭아 영역은 진한 Ink 사용.
- 런처 두 전경에 15% 비율 여백. 알파가 5를 넘는 픽셀의 최대 반경은 컬러 31.81dp, 단색 30.78dp로 33dp 안전 반경 안에 있습니다.
- 앱 versionCode 2 / versionName 0.1.1. 이전 전달 v0.1.0과 서명 일치 및 APK 서명 유효성을 확인했습니다. 패키지/DB 구조는 유지했습니다.

전달 파일: `GalliPick-v0.1.1-debug.apk`, 63,351,551 bytes.

```text
SHA-256: 3c1fbb11fb07b4a180ec043c24a470e6d892874bbf561d603ce31c036cc5b3b8
```

아래 v0.1.0 기록은 최초 구현의 별도 검증 이력입니다. 실기기 휴지통/복원·대량 미디어·폴더블 검증이 필요하다는 기존 한계는 계속 적용됩니다.

---

기준: 2026-10-31, GalliPick 0.1.0.

## 로컬 검증

Java 17, Gradle 8.13, Android SDK 36에서 아래 작업을 실행했습니다.

```bash
gradle clean testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --max-workers=2
```

- 전체 작업 성공.
- JVM 단위 테스트 38개, 실패/오류 0개.
  - 필터 10개: 교집합·날짜 경계·날짜 종류·폴더·형식·방향·64비트 크기·정렬.
  - 보존 정책 12개: 즐겨찾기/보호 파일 제외, 최소 사본 유지, 동일 URI 정규화, 알 수 없는 날짜.
  - SHA-256 6개: 알려진 해시, 여러 버퍼, 취소, 읽기 실패, 스트림 소유권.
  - 포맷 2개.
  - 캐시/보호 정책 8개: 폴더 경계, 루트 폴더, 파일 버전, 실패/오래된 해시 제외.
- Android lint 오류 0개. 스타일/API 권장 경고는 별도로 남아 있으며 실패를 숨기기 위한 baseline/abort 비활성화는 사용하지 않습니다.
- debug 앱 APK와 기기용 androidTest APK 빌드 성공.
- APK 메타데이터: 패키지 `com.hebi.gallipick`, versionCode 1, versionName 0.1.0, minSdk 30, targetSdk 36.
- APK v2 서명 검증 성공. 개발용 debug 서명이며 정식 배포 서명은 아닙니다.
- 최종 APK 권한에서 INTERNET 없음 확인.
- `git diff --check` 통과.

## 검토 후 보완

- 중복 보관본이 외부에서 변경/삭제된 경우 최종 휴지통 요청 차단.
- 선택 시 중복 묶음 정보를 보관하고 비동기 검증 전에 요청에 고정.
- 앱 복귀/권한 결과마다 이전 목록을 숨기고 현재 접근 범위 재조회 후 표시.
- 개별 보호와 상위 폴더 보호를 구별하여 해제 버튼 상태·설명 수정.
- 선택 상자·필터·탭에 접근성 선택 상태 제공.
- 위치 메타데이터가 가려진 파일을 원본으로 오인하지 않도록 `setRequireOriginal` 사용, 읽기 불가 원본 제외.
- 대량 모두 선택을 한 번의 상태 갱신으로 처리.

## 기기 통합 검사

GitHub Actions `Android device tests`가 Android 16/API 36 에뮬레이터에서 다음을 검증했습니다. **통합 검사 4개, 실패/오류 0개**입니다.

1. 실제 MediaStore에 동일 PNG 두 개를 기록한 후 WorkManager 중복 검사.
2. 남길 사본을 외부 삭제했을 때 생존 사본 검증 실패.
3. 보호 설정의 새로고침·캐시 초기화 후 유지와 최종 휴지통 후보 제외.
4. 같은 PARTIAL 상태로 다시 접근을 검증할 때 저장된 목록·중복·휴지통 숨김.
5. 홈·파일·필터·중복·정리·설정 화면 진입, 홈/필터 화면 캡처.

PARTIAL 테스트는 ViewModel의 표시 차단을 검증합니다. Android 사진 선택창을 통한 실제 권한 변경을 자동 조작하는 테스트는 아닙니다.

최종 검증 소스 커밋은 `b0e8186806849d6fc1156b52e615be5f21ed983c`입니다.

- [GitHub 빌드·단위 검사·lint 통과](https://github.com/hebi10/GalliPick/actions/runs/41196795075)
- [Android 16 통합 검사 통과](https://github.com/hebi10/GalliPick/actions/runs/41196795024)
- 홈·필터의 실제 화면 캡처를 내려받아 간격·정렬·입력란·고정 버튼을 확인했습니다. 캡처는 기기 검사 보고서 artifact에 포함됩니다.
- 테스트 후처리의 동일 파일 이중 삭제를 수정했습니다. 화면 캡처는 Android 테스트의 additional output 경로로 수집합니다.

최종 전달 파일: `GalliPick-v0.1.0-debug.apk`, 62,475,273 bytes.

```text
SHA-256: 2dd0d4a0ef85505a234dc9286e3221ea81e8104487c9cd225d54f87075e2f55b
```

## 아직 확인하지 않은 범위

- 실제 사용자 폰에서 시스템 승인/취소 후 휴지통 이동과 복원, 제조사별 휴지통 표시 차이.
- Android 11~15 실기기 동작, Android 16의 장시간 작업 제한에 걸리는 실제 대용량 파일.
- 수만 미디어·SD 카드 탈착·실제 폴더블 펼침/접힘 성능.
- 화질 판단, 유사 사진/영상, 클라우드 미디어는 이번 구현 범위에 포함하지 않습니다.

이 항목들은 자동 검사 통과 여부와 별개로 실기기 검증이 필요합니다.

## 2026-11-03 — 0.1.7 (8) Play 업로드 준비

- [PR #4](https://github.com/hebi10/GalliPick/pull/4)를 main에 통합했습니다. 검증 소스 `b6812e31264eb03269532c8b21a75731501f1870`와 통합 커밋 `ffd3561aaadcee95d3f74b0ebd079bffe2fddab6`의 tree는 동일합니다.
- [Android verification](https://github.com/hebi10/GalliPick/actions/runs/41583364590) 성공, 기존 25개와 새 AAB 검증 14개를 합친 Python 검사 39개가 로컬 및 CI에서 통과했습니다.
- [전체 Android 기기 검사](https://github.com/hebi10/GalliPick/actions/runs/41583364589) 80개 통과. 독립 소스 검토 P1/P2 없음.
- [고정 키 AAB 빌드](https://github.com/hebi10/GalliPick/actions/runs/41584050395)는 기존 Actions 서명 자료의 공개 인증서 지문 불일치로 복구 단계에서 중단했습니다. AAB 산출물은 생성되지 않았습니다.
- 원본 사용자 제공 키와 현재 고정 인증서의 일치, 개인 키 접근 및 올바른 복구 ZIP 구조를 비공개로 확인했습니다. 자격 증명 갱신은 사용자 수동 입력·저장 단계에 대기 중입니다.
- Play 내부 테스트에 출시명 `0.1.7 (8)`과 한국어 변경 설명을 초안으로 저장했습니다. 새 AAB 업로드, 검토 제출 및 출시는 수행되지 않았습니다.
- 키 변경이 완료되면 실패한 AAB job만 재실행하고, 산출물의 패키지·버전·서명·체크섬을 확인한 뒤 같은 Play 초안에 업로드합니다.

## 2026-11-03 — QuickCut 방식의 AAB 준비·별도 서명

- [PR #5](https://github.com/hebi10/GalliPick/pull/5) 통합 완료. 검증·빌드 소스 `7f17970c136fb1d0c0317720e2f321580232c8e8`와 main 통합 `ef7bbb9fa4555416de48194397683cd2e444ac02`는 tree `bff9b9fc2928a8fa3b5cc9a5d4e7c7c456afd8a2`가 동일합니다.
- [Android verification](https://github.com/hebi10/GalliPick/actions/runs/41586643978) 성공; Python 검증 46개 통과. [전체 Android 기기 검사](https://github.com/hebi10/GalliPick/actions/runs/41586643977) 80개 통과. 독립 리뷰 P1/P2 없음.
- [서명 전 AAB 준비](https://github.com/hebi10/GalliPick/actions/runs/41586639777): 실제 별도 variant 빌드와 lint, bundletool·manifest·unsigned·private-material 검사 성공. Actions artifact13208865305의 SHA-256을 대조하고 ZIP 무결성을 확인했습니다.
- 기존 검증된 원본 키로 정확한 준비물을 비공개 임시 범위에서 JDK17 JAR 서명했습니다. 기존 full AAB verifier와 bundletool이 통과했고, 모든150개 앱 payload가 준비물과 동일하며 고정 인증서로 정상 서명됨을 확인했습니다.
- 결과: `GalliPick-v0.1.7-release.aab`, 패키지 `com.hebi.gallipick`, 버전0.1.7/code8, min30/target36, non-debuggable,13,139,649 bytes. SHA-256: `ca4f4cb38023b659f20e9736c6329211c87b2d36775b6b897973802ecb2d83ad`. 서명된 파일은 사용자 전달용으로 저장했습니다.
- Play 내부 테스트0.1.7(8) 초안은 유지했습니다. 브라우저 host 정책이 ChatGPT 파일 페이지로의 자동 이동을 차단해 PC로 파일을 옮기는 단계는 사용자 다운로드가 필요합니다. 아직 새 AAB 업로드·검토 제출·출시는 완료되지 않았습니다.

## 2026-11-03 — Play AAB 업로드 완료

- 사용자가 내려받아 첨부한 AAB의 크기13,139,649bytes와 SHA-256 `ca4f4cb38023b659f20e9736c6329211c87b2d36775b6b897973802ecb2d83ad`가 기존 검증된 서명 AAB와 동일함을 확인했습니다.
- 사용자 PC Downloads의 `GalliPick-v0.1.7-release.aab`를 정상 파일 선택 경로로 업로드했습니다. Play Console의 `App Bundle 1개 업로드됨` 성공 알림 및 새 아티팩트 `8 (0.1.7)` 표시를 확인했습니다.
- 대상은 `com.hebi.gallipick`의 기존 내부 테스트 초안입니다. API30이상/targetSDK36 아티팩트가 수락되었으며, 출시명0.1.7(8)과 한국어 변경 설명을 유지했습니다.
- `임시보관함에 저장` 실행 후 저장 성공 안내와 비활성 저장 버튼을 확인했습니다. [저장된 Play 초안](https://play.google.com/console/u/0/developers/5537932067090246638/app/4972863093038524623/tracks/4701553322848710324/releases/2/prepare).
- 이번 요청의 AAB 업로드·초안 저장은 완료했습니다. 검토 제출·테스터 배포·프로덕션 출시는 실행하지 않았습니다.

## 2026-11-04 — GitHub Actions 비공개 테스트 자동화

- [PR #7](https://github.com/hebi10/GalliPick/pull/7)을 main `9fc243a900212f2b3cc1680e2f57b3597b5acc3c`에 통합했습니다. 버전 태그 자동 실행과 수동 check/build/deploy, 고정 서명 APK·AAB 검증, 기존 비공개 트랙 전용 Publisher 클라이언트를 추가했습니다. 앱 기능·버전은 변경하지 않았습니다.
- JDK17 전체 릴리스 도구 테스트 66개 통과, 생략 0개. actionlint와 shell/source/auth 구성 검사 통과. 독립 코드 검토 및 GitHub 자동 코드 검토의 필수 수정 사항 없음.
- [Android verification](https://github.com/hebi10/GalliPick/actions/runs/41783449827) 성공. [Android 16 기기 검사](https://github.com/hebi10/GalliPick/actions/runs/41783449822) 80개 완료·빌드 성공.
- 실제 [Play check #1](https://github.com/hebi10/GalliPick/actions/runs/41784363190)을 main에서 실행했습니다. 정확한 소스·main 포함 관계 검사 성공. `play-preflight`는 Play 인증 미설정으로, `signing-check`는 복구 자료와 고정 공개 인증서 지문 불일치로 각각 중단했습니다.
- `PLAY_CLOSED_TRACK=alpha` 설정 완료. Play Console의 현재 비공개 제공 버전은 0.1.6/code7이고 내부 초안에 code8이 이미 업로드되어 있습니다. API 트랙 확인은 배포 계정 연결 후 수행하며 다음 새 업로드 코드는 최소9여야 합니다.
- 이번 점검에서 APK·AAB 생성이나 Play 제출은 수행되지 않았습니다. 현재 JKS에 맞는 서명 Secret 갱신과 GalliPick에 한정한 배포 계정·GitHub 인증 연결이 남아 있습니다.

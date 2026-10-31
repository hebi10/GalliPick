# GalliPick Implementation Plan

> **For agentic workers:** use executing-plans with independent domain/data/UI work delegated in this session.

**Goal:** 실행 가능한 로컬 미디어 정리 앱과 자동 검증 환경을 GalliPick 저장소에 구성합니다.

**Architecture:** Compose 화면과 ViewModel, MediaStore/Room 저장소, 순수 Kotlin 필터·중복 정책을 분리합니다. WorkManager가 파일 단위 검사 진행을 보관합니다.

**Tech Stack:** Kotlin 2.2.21, Compose, Room 2.8.3, WorkManager 2.10.1, AGP 8.13.2, Gradle 8.13, Java 17.

**Spec:** ../specs/2026-10-31-gallipick-design.md

## Global Constraints
- minSdk 30, target/compileSdk 36. 인터넷 권한과 자동 영구 삭제 금지.
- MediaItem/MediaFilter/ScanState는 domain/Models.kt를 공통 계약으로 사용.
- 추천·최종 휴지통 요청 모두 즐겨찾기/보호 파일 제외.
- 사용자 기획의 단계 구분 유지. 유사 분석은 미구현 표시.

## Review Focus
- 4GB 초과 용량과 날짜 단위 변환: Long 값 및 밀리초 변환 검증.
- 권한 일부 허용/회수: 보이는 데이터만 사용하고 기존 보호 설정 보존.
- 파일 변경/삭제: 해시 무효화 및 휴지통 직전 fingerprint 재확인.
- 백그라운드 중단: 완료 파일 체크포인트 유지, 불완전 해시는 저장 금지.
- 모든 사본 보호/즐겨찾기: 추천 용량 0, 삭제 후보 없음.

## Task 1: 필터·중복 정책
Files: domain/MediaQuery.kt, DuplicatePolicy.kt, MediaHasher.kt, app/src/test.
- [ ] 교집합 필터, 경계 날짜, 큰 용량, 정렬 tie, 해시 알려진 값, 보존 정책 테스트 작성.
- [ ] MediaQuery.apply, DuplicatePolicy, MediaHasher.sha256 구현.
- [ ] testDebugUnitTest 통과.

## Task 2: 미디어·분석 저장소
Files: data/*.kt.
- [ ] 접근 가능한 MediaStore 사진/영상과 휴지통 조회, Room 캐시/보호 테이블 구현.
- [ ] 중복 후보 파일 해시 검사, pause/resume, 캐시 무효화 구현.
- [ ] revalidate / revalidateForRestore로 최종 요청 전 상태 검증.
- [ ] 빌드·lint 및 검사 상태 회복 시나리오 검증.

## Task 3: 화면과 시스템 작업
Files: ui/*.kt, AppViewModel.kt, AppSettings.kt, MainActivity.kt.
- [ ] 홈/파일/중복/정리/설정 및 휴지통, 필터, 프리셋, 상세 미리보기 구현.
- [ ] Android 버전별 권한/일부 접근 UI, 휴지통 승인/취소/복원 연결.
- [ ] 임의 데이터 없는 실제 상태와 읽기 실패 피드백 확인.

## Task 4: 통합·전달
Files: build files, .github/workflows/android.yml, README.md, docs/verification.md.
- [ ] Gradle wrapper, SDK 설정, JVM 테스트·lint·APK 빌드.
- [ ] 독립 코드 검토와 중대한 문제 수정.
- [ ] 저장소 반영 및 Actions 검증 결과 확인. 실기기 미검증 범위를 기록.

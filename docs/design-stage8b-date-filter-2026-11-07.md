# GalliPick 8-B — 날짜 기준·프리셋·안내 개선

기준: 2026-11-07 사용자가 제시한 필터 2번 화면, Google Drive 8단계 기획 및 8-A가 완료된 main.

## 현재 기능과 정확한 설명
- 기본 날짜 기준 `DateField.CAPTURED`는 `MediaItem.capturedAt`을 사용합니다. `MediaStore.DATE_TAKEN`(촬영 메타데이터)이 유효하면 해당 값을 사용하고, 없으면 `DATE_ADDED`(기기 미디어 목록 추가 날짜)를 대신 사용합니다.
- `DateField.ADDED`는 기기 미디어 등록일이며 원본 촬영일과 동일함을 보장하지 않습니다.
- `DateField.MODIFIED`는 마지막 파일 수정일입니다.
- 기존 날짜 필터의 실데이터 처리·시간대·앱 저장 정책은 변경하지 않습니다.

## 구현
1. 날짜 범위 첫 부분에 현재 선택 기준명(`촬영일 기준`/추가일 기준/수정일 기준)과 이해하기 쉬운 설명 추가.
2. 기존 최근 7일/30일/이번 달/지난 달/전체 기간에 `오늘` 선택 및 `직접 선택` 달력 진입 추가.
3. 적용한 날짜 범위를 다시 불러올 때 날짜 양끝(시작일·종료일)을 기준으로 현재 프리셋(오늘·7일·30일·이번 달·지난 달·전체 기간)을 복원합니다. 수동 입력은 직접 선택 상태로 구분합니다.
4. 이미 구현된 종료일 포함·날짜 유효성·키보드 입력·달력·하단 적용 버튼은 변경하지 않습니다.

## QA
- Android verification — 컴파일/단위 테스트/Lint.
- Android device tests — 새 날짜 설명/기준 전환, `오늘` 선택·적용·다시 열기 및 직접 선택 달력 접근.
- Adaptive UI verification and native captures — 휴대폰 세로/가로/160% 큰 글씨, 태블릿 세로/가로. 실제 필터 화면 `08-files-filter-dialog`과 키보드 상태 `08b` 확인.
- 기존 날짜 역전·윤년·월초/월말·키보드 입력·최근 7일·완료 후 5프로필 전부 회귀 검증.
- 최종 3종 CI가 성공한 PR 커밋만 main에 병합, 실패 시 수정·재검증.
- Google Play alpha v0.2.5(code16)은 변경/배포하지 않습니다.

## 후속
8-C 중복 묶음 가로 스크롤 → 8-D 비교 화면 단일 스트립 → 8-E 결과 용량 정확도 → 8-F 홈 아이콘 → 8-G 최종 QA.


## 2026-11-07 최종 검증 및 main 병합
- PR #46: https://github.com/hebi10/GalliPick/pull/46 — merged `4d849cbab17e124f5e00644e60f55abd10221736`
- 최종 검증 대상 SHA: `ac4bcb7a97bbabfaaa877648f003a51d2922906f`
- Android verification: https://github.com/hebi10/GalliPick/actions/runs/42343178794 — success
- Android device tests: https://github.com/hebi10/GalliPick/actions/runs/42343178729 — success
- 5프로필 native 캡처: https://github.com/hebi10/GalliPick/actions/runs/42343155540 — success
- 5개 ZIP Drive 보관: https://drive.google.com/drive/folders/1HDcQhQrg1eJNIzMD5ui07-Q6Jg-XsxL2
- 휴대폰 세로 및 태블릿 세로 실제 필터 캡처에서 날짜 기준·촬영일 정보 부재시 추가일 사용 안내, 오늘·직접 선택 프리셋, 시작일/종료일, 적용 버튼을 검수했습니다.
- 날짜 필터·파일 안전 정책을 보존하며 Play 비공개 테스트 v0.2.5(code16)는 재배포하지 않았습니다.

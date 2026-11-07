# GalliPick 8-E — 휴지통 결과 실제 확인 용량

기준: v0.2.5/code16, 8-C·8-D 후속 작업. `main` 병합 전 필수 Android 검증.

## 변경
- MediaStore에서 URI/ID/볼륨/형식/원래 크기/볼륨 버전이 일치하고 실제 IS_TRASHED 상태가 요청에 맞게 변경된 파일만 "확인 완료"로 집계합니다.
- `TrashOutcome.confirmedCompletedBytes`는 확인한 파일만 합산합니다. 중복 URI는 1회 집계하며 크기는 음수 방어 및 Long.MAX_VALUE 포화 합산입니다.
- 부분 성공·변경 없음·확인 불가 파일 크기는 완료 용량에 포함하지 않습니다.
- 복원 화면은 "복원 확인 파일 크기", 휴지통 이동 화면은 "휴지통 이동 확인 파일 크기"로 구분합니다.
- "요청한 파일의 용량"과 "휴지통 이동 확인 파일 크기"는 별도 항목입니다. 여유 저장공간 확보량은 계산하거나 주장하지 않습니다.
- 파일을 영구 삭제하는 기능은 추가하지 않습니다. 기존 Android 확인·재검증·보호/즐겨찾기 정책과 5개 상태 결과 모달을 유지합니다.

## 검증 기준
1. Android verification (unit/lint/compile), Android device tests, 5-profile native captures 모두 최종 SHA로 통과
2. JVM: 부분 성공, 중복 URI, 제공자 파일 정체성 불일치, 복원, Long 포화 합산
3. Instrumentation: 실제 MediaStore 휴지통/복원 상태 조회 및 확인된 용량 일치
4. 결과 UI: 성공/부분 성공/취소/실패/확인 불가, 요청 용량과 확인된 용량 구별
5. 320dp, 160% 글자, 휴대폰 가로, 태블릿 세로·가로에서 용량과 버튼 표시

8-F 아이콘 및 8-G 통합 검증 전에는 Google Play 릴리스 버전과 트랙을 변경하지 않습니다.

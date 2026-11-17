# 클라우드 네 화면 디자인 및 검증

기준은 GalliPick main `91cd945` (0.2.15)과 같은 개발 작업 폴더의 자동 구독 확인 변경이다. 첨부된 클라우드 홈, 요금제·결제, 내 보관함, 원본 백업을 개선한다.

[클라우드 UI 통합 기획서](https://docs.google.com/document/d/1z-AAAsj4-L9mYJdWzrF_3Ue8bV3QWud9JMw8Ai4dIdU/edit)의 계정별 상태 분리, Play 실가격 표시, 원본 검증 정책을 적용했다. 요금제·결제 진입점은 현재 main의 배치를 따른다. 정식 상품의 용량, 가격, 전환 시점은 별도 기획 항목이다.

## 생성 시안

[4개 화면 시안](reference.png). 요금제·결제, 내 보관함, 원본 백업의 기존 캡처를 참조했다. 계정 개인정보는 생성에 사용하지 않았다.

### 생성 프롬프트

> Create a single landscape design handoff image for GalliPick, a Korean native Android photo organizer, showing FOUR evenly sized uncropped 360dp wide portrait Android screens side by side. Keep all content visible. Design an elegant, functional, compact Android settings experience, Korean-readable sans serif and understated lines. Match existing neutral GalliPick surfaces: background very light cool gray (#F4F6F8), solid white cards with thin cool gray outline, navy-black (#191E27) text and primary button, secondary gray text, soft gray-blue square icon backgrounds, 16–20dp rounded corners. Use consistent simple outlined Material-like icons, no gradients, no hero photographs, no fake decorative text, no floating navigation. Use small header 24sp, title 17sp, supportive 13sp. Cloud landing artboard 1: top title '클라우드', compact account card with circular person icon, primary label '테스트 계정', two simple outlined buttons 'Google 계정 변경' and '로그아웃' side-by-side, account only fake identity. One feature link with card/membership icon '요금제·결제' and helper '현재 요금제와 결제 내역을 확인해요' and right chevron. Two grouped settings lists, visible crisp separators and clear actionable rows with distinct line icons: '내 보관함', '원본 백업', '자동 백업', '공간 확보 검토', brief helper labels in keeping with real function. Screen 2 '요금제·결제' with back arrow, visible filled plan card '이용 중인 요금제' and '클라우드 1 GiB', active chip '이용 중', second line '클라우드 원본 보관 · 광고 제거' and actions lower in separate bordered card 'Google Play에서 구독 관리', '구매 복구', '상태 새로고침'. Show only a single active plan, no fake prices, no upgrade CTA, no quotas beyond 1 GiB. Screen 3 '내 보관함' with back arrow, secondary outlined '목록 새로고침' at top of content, centered empty state with simple cloud/photo outline icon inside a softly tinted 56dp square, heading '보관한 원본이 없어요', line explaining backed-up photos and videos will appear here. Screen 4 '원본 백업' with back arrow, white feature panel with circular arrow/cloud upload icon, bold '사진·동영상 원본 보관', helpful text for one photo or video per upload and up to 64 MiB, privacy note stating embedded location information is included, full-width dark primary '파일 선택' button. Align treatments across all screens, with button hierarchy and accessible controls. This is a visual implementation reference, not an advertising poster. Show each app panel at identical width with no device bezel or surrounding headline. Do not add features or personal information.

## Compose 반영 계획

1. `CloudUiFoundation.kt`, `CloudAccountSettings.kt`에서 실제 뒤로 버튼을 접근성 이름이 있는 아이콘으로 바꾼다. 계정과 요금제 진입점을 강조하고 보관함/원본 백업 및 자동 백업/공간 확보 진입점을 묶는다. 기존 앱 팔레트, 벡터 아이콘, 48dp 터치 영역을 쓴다. 좁은 화면이나 큰 글씨에서는 계정 버튼이 줄을 나눌 수 있어야 한다.
2. `CloudBillingSection.kt`에서 서버 요금제 상태, Play에서 받은 실제 상품과 가격, 조작을 구분한다. 서버로 확인한 용량·사용량·유효기간·광고 혜택만 표시하고, 신규 구매·자동 검증·수동 복구 동작을 보존한다. 활성 요금제 관리에는 공식 Play 링크를 사용한다. 현재 개발 테스트의 월간 기본 요금제에는 전환 대상이 없다.
3. `CloudLibrarySection.kt`에 조회·새로고침과 비어 있을 때의 안내 영역을 구분한다. 실제 목록·미리보기·복원·오류 동작을 보존한다.
4. `CloudBackupSection.kt`에 한 파일 제한, 크기 제한, 원본에 포함된 정보 안내를 배치하고 파일 선택을 강조한다. 권한, 업로드, 확인 및 서버 검증은 기존 동작을 따른다.
5. 빌드와 기존 단위·UI 테스트를 실행한다. 앱 데이터가 있는 에뮬레이터는 동일 서명 APK의 덮어쓰기로 갱신한다. 실제 화면의 라이트 테마, 밀도, 글꼴 배율을 기록하고 동일 조건의 시안 비율로 대조한다. 구독 상태는 실제 계정에서 나온 상태를 기록하고 필요한 상태는 계측 테스트에서 검증한다. 앱 계정과 미디어 데이터를 보존한다.


## 에뮬레이터 대조

Android 16/API 36의 세로 화면(1080×2424 px, 420 dpi, 라이트 테마, 글꼴 배율 1.0)에서 확인했다. [시안과 실제 화면 대조](comparison.png)의 윗줄은 시안, 아랫줄은 앱 캡처다. 클라우드 영역 시작점을 맞췄으며 각 화면의 가로 폭을 250 px로 조정해 같은 비율로 배치했다. 화면 높이는 각 기기의 비율을 따랐다. 원본 앱 캡처에는 GalliPick 상단과 하단 탭이 포함된다. 홈 캡처의 계정 이름·이메일 영역은 가렸다.

| 화면 | 기준 | 실제 결과 | 차이 | 수정 방안 및 결과 |
| --- | --- | --- | --- | --- |
| [클라우드 홈](emulator-overview-redacted.png) | 계정과 결제를 위에 배치하고 메뉴를 두 쌍으로 묶음 | 계정과 좌우 버튼, 단독 결제 진입점, 두 개의 메뉴 묶음 표시 | 실제 앱에는 계정 이메일, 앱 공통 영역과 기존 Material 아이콘이 있음 | 실제 계정은 이름·이메일을 확인할 수 있게 표시한다. 좁은 화면과 큰 글씨에서는 계정 버튼을 세로로 배치한다. |
| [요금제·결제](emulator-billing.png) | 활성 요금제 카드, 구독 관리·구매 복구·새로고침 | 테스트 계정의 활성 1 GiB와 광고 제거, 사용량, 종료일, 자동 갱신 상태 표시 | 실제 서버 사용량과 구독 기간을 표시해 첫 카드가 더 높음. 첫 확인에서 기기 언어에 맞춰 종료일이 영어로 표시됐음 | 종료일을 한국어로 맞췄으며 다시 캡처해 확인했다. Google Play의 GalliPick 구독 관리 화면이 열리고 결제 화면으로 돌아오는 동작을 확인했다. |
| [내 보관함](emulator-library.png) | 새로고침과 가운데 배치한 빈 상태 | 빈 상태, 목록 새로고침, 뒤로 동작 확인 | 기존 사진 라이브러리 아이콘 사용. 첫 확인에서 시안 대비 아이콘과 제목이 작았음 | 아이콘을 96dp로 키우고 제목을 높여 다시 캡처했다. |
| [원본 백업](emulator-backup.png) | 업로드 안내, 64 MiB와 촬영 위치 안내, 하단 주요 동작 | 미디어 권한이 없는 상태에서 안내와 접근 허용 표시 | 현재 기기는 파일 선택 전 권한 단계에 있음. 첫 확인에서는 접근 허용이 작은 보조 버튼이었음 | 업로드 아이콘과 제목을 키우고 허용을 전체 폭 주요 버튼으로 바꿨다. 파일 선택은 기존 접근 조건을 따른다. 실제 파일 전송은 이번 화면 확인에서 실행하지 않았다. |

## 검증 결과

- `:app:testDebugUnitTest` 221개 통과, `:app:lintDebug` 오류 0개, 디버그 앱 및 계측 APK 빌드 성공.
- 앱 인증서 일치를 확인하고 기존 에뮬레이터 설치 위에 갱신했다. AndroidJUnitRunner로 `CloudScreenNavigationTest`와 `CloudCheckoutUiTest` 5개가 통과했다. 계측 결제 복귀 후 수동 복구 없이 검증된 상태와 관리 동작이 나타나고 홈 배너가 사라지는 흐름을 포함한다.
- 실제 테스트 계정에서는 활성 1 GiB, 홈 테스트 배너 미표시, 구독 관리 링크의 Google Play 진입 및 앱 복귀를 확인했다. 이번 검증에서는 새 결제나 요금제 전환을 실행하지 않았다.
- 병렬 작업에서 홈·공통 UI와 탐색 테스트, 보관함·백업 UI, Play 양방향 구독 전환 가능 범위를 각각 검토했다. 관리 화면 복귀 시 서버 조회를 추가했으며, 통합 후 앱 빌드와 기기 검증을 진행했다.
- 실제 요금제 변경에는 판매 지역의 전환 대상 기본 요금제, 결제 적용 시점과 구독 한도 정책, Play 구매 토큰 교체 및 서버 검증이 필요하다. 현재 시험 계정의 상품은 1 GiB 월간 한 종류다. [Play Console 상품 안내](https://support.google.com/googleplay/android-developer/answer/140504?hl=ko) · [Play 구독 변경 가이드](https://developer.android.com/google/play/billing/subscriptions#allow-users-to-upgrade-downgrade-or-change-their-subscription)

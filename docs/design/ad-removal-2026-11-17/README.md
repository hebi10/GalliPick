# 광고 표시부터 구독 완료까지

기준은 main 0.2.15 `91cd945`와 같은 작업 폴더의 자동 구매 확인 및 클라우드 디자인 변경이다. 첫 방문의 무료 사용, 광고와 앱 조작의 분리, 광고 제거 진입, Google 로그인, 월간 가격 확인, 결제·보류·취소, 광고 없는 홈 복귀를 하나의 경로로 검증한다.

[디자인 시안](reference.png)은 실제 홈과 이전에 확인한 결제 화면을 참고했다. 실제 상품은 현재 개발 테스트의 월간 Cloud 1 GiB만 사용한다.

## 생성 프롬프트

> Design one landscape handoff sheet of four Korean Android screens arranged side by side for GalliPick, using the attached real home and billing screenshots as visual references. Match their familiar minimal gray palette, black typography, warm cat branding on home only, Material icons, white bordered rounded cards and existing bottom navigation. Keep each portrait screen intact, width and height consistent. Screen 1: existing GalliPick home, keep its organizer hero and photo permissions or storage card. Add one compact low-emphasis app-owned row after the home hero, with a muted icon, '광고 제거', subtitle '클라우드 구독 혜택 보기', and a chevron. A separate static test banner labeled '광고' is anchored near the bottom with distinct nonclickable whitespace before the navigation tabs; the banner has no controls and no call to action. Screen 2: '광고 제거' title with back arrow, a single clear account card with cloud icon, '홈 배너 광고 제거', '클라우드 구독에 포함됩니다', note that device photo organization may continue without subscription, and a dark 'Google로 로그인' button. Screen 3: '요금제·결제' with back arrow, no-plan offer card with the heading '신청 가능한 요금제', cloud storage and home banner removal benefits, Play verified recurring monthly price shown as a generic placeholder rather than invented price, explicit auto-renew and Google Play cancellation info, prominent 'Google Play에서 선택', and subordinate '구매 복구'. Screen 4: successful return on '요금제·결제', visibly confirmed '홈 배너 광고가 제거됐어요', a filled '홈으로' button, and the verified '클라우드 1 GiB' plan and its usage below. Keep native Android UI scale, use readable Korean and restrained contrast. No invented products, operating price, personal name, email, or extra call to action within the advertising rectangle. This is an implementation reference, not a sales poster.

## 반영 순서

1. 홈 본문과 설정에 광고 제거·구독 진입점을 제공한다. 광고 표시 영역과 앱 버튼을 구분하고 광고가 로드되지 않아도 요금제 확인 경로를 찾을 수 있게 한다. 홈의 활성 구독 계정에 광고 제거 신청을 반복 노출하지 않는다.
2. Cloud 내 광고 제거 진입에는 초기 로그인 안내, 계정 선택 상태, 로그인 후 결제 자동 연결과 출발 탭으로 돌아가는 동작을 제공한다. 기존 클라우드 탭과 계정 전환의 접근 제한을 유지한다.
3. 결제 전에 실제 Play 가격, 월간 자동 갱신, 취소 수단과 혜택을 보여준다. 결제 확인 중 중복 신청을 막고, 취소·실패·보류의 다음 동작을 안내한다. 실제 서버로 확인한 활성 상태에만 광고 제거 완료와 홈 복귀를 표시한다.
4. 홈에서 현재 UID의 서버 검증 결과를 즉시 반영한다. 결제 화면을 벗어나거나 앱을 다시 열었을 때, Play의 누락된 구매를 서버에 자동으로 확인할 수 있게 한다. 광고 동의와 검증 실패의 광고 요청 제한을 유지한다.
5. 회귀 계측·단위 테스트, lint·빌드, 현재 앱의 인증서를 보존하는 덮어쓰기 및 에뮬레이터 확인을 수행한다. 실제 서버/Play 상태와 모의 가입·보류·카드 결제를 구분한다. 현재 계정과 미디어 접근 상태를 바꾸지 않고 화면 시안과 비교한다.

기획 근거는 [기존 클라우드 UI 기획서](https://docs.google.com/document/d/1z-AAAsj4-L9mYJdWzrF_3Ue8bV3QWud9JMw8Ai4dIdU/edit)와 [광고 운영 문서](../../ads-01-admob-play-console.md), [AdMob 배너 배치](https://support.google.com/admob/answer/6275335), [Play 결제 처리](https://developer.android.com/google/play/billing/integrate)이다.


## 실행 결과

[시안과 에뮬레이터 대조](comparison.png)의 위는 생성 시안, 아래는 동일 흐름의 앱 화면이다. Android 16/API 36, 세로 1080×2424 px, 420 dpi, 라이트 테마와 기본 글꼴 배율에서 캡처했다. 각 화면의 원본 비율을 유지하며 가로 폭을 234 px로 맞췄다. 로그인과 결제 완료 캡처에는 계측용 계정과 구매 응답을 사용했다.

| 단계 | 시안 기준 | 실제 화면 | 차이와 반영 결과 |
| --- | --- | --- | --- |
| 첫 방문 | 앱의 광고 제거 버튼과 하단 배너가 분리됨 | [미로그인 홈](01-home-with-preview-ad.png)과 [공식 SDK 테스트 배너](06-home-with-sdk-test-ad.png) | 앱의 기존 고양이 홈과 아이콘을 사용했다. 배너는 광고 위아래의 비클릭 여백과 선으로 앱 조작과 구분한다. |
| 로그인 안내 | 광고 제거 혜택, 무료 이용과 로그인 버튼 | [광고 제거 로그인](02-ad-removal-sign-in.png) | 모든 하위 탭에 공통 앱 헤더가 상단에 표시된다. 로그인 버튼 탭 후 요금제 화면으로 이어짐을 확인했다. |
| 결제 전 | 요금제, 정기 결제 조건과 주요 동작 | [계측 화면](03-available-plan.png), [실제 Play 요금제](live-available-plan.png) | 첫 화면의 중복 상태 카드를 제거했다. 상품명을 반복하던 버튼을 ‘Google Play로 계속’으로 바꾸고, 별도 시험 결제 확인창에 시험 결제수단 안내를 유지했다. |
| 승인과 보류 | 서버 확인 뒤 배너 제거와 홈 복귀 | [승인 후 화면](04-verified-purchase.png), [광고 없는 홈](05-home-after-verification.png), [결제 보류](07-payment-pending.png) | 활성 확인 시 홈 버튼과 실제로 확인된 요금제 정보를 표시한다. 보류 중에는 결제 상태 확인만 제공하며 확인 전 홈으로 가도 배너가 잠깐 표시되지 않는다. |
| 결제 취소 | 안전하게 요금제로 돌아옴 | [실제 Play 창에서 취소 후](live-checkout-cancelled.png) | 시험 결제수단을 표시하는 Play 결제창을 열었다. 결제창을 닫은 뒤 앱의 취소 안내, 신청 가능 요금제, 이어서 홈 복귀를 확인했다. |

## 검증 범위

- Android JVM 227개, 계측 24개, 별도 공식 AdMob SDK 시험 배너 계측 1개 통과. Debug 앱·계측 APK 빌드와 lint 통과. 기존 의존성 등의 lint 권고가 남아 있다.
- 현재 에뮬레이터의 서명과 일치하는 APK를 덮어 설치했다. [기존 계정으로 실행한 홈](live-home.png)에서 광고 제거 진입, Play가 반환한 월간 요금과 자동 갱신 안내, 시험 카드 노출, 실제 Play 창 취소, 홈 복귀를 확인했다. 실제 새 결제 승인 대신 계측용 승인 응답으로 구매 직후 광고 제거를 검증했다.
- 계측에서 미로그인 → 요금제 연결, 결제 콜백 누락 복구, 보류 중 중복 신청 차단, 앱 실행 시 서버에 미등록된 Play 구매 자동 확인, 계정과 기존 탐색, 큰 글씨·넓은 화면의 홈·설정 동작을 확인했다.
- 병렬로 홈·설정·탐색과 계측, Play 결제 처리와 상태 전이, 광고 SDK와 계정별 광고 권한을 구현·검토했다. 통합 단계에서는 홈으로 돌아가는 중 광고가 잠깐 다시 나올 수 있는 경우와 시안에서 확인된 요금제 문구를 보완했다.
- 운영 광고, 다른 지역의 개인정보 동의 양식, 새 테스트 카드 승인, 실기기는 이번 검증 범위에 포함되지 않았다.

import { initializeApp } from "https://www.gstatic.com/firebasejs/13.4.0/firebase-app.js";
import {
  getAuth, GoogleAuthProvider, browserSessionPersistence, setPersistence,
  signInWithPopup, reauthenticateWithPopup, signOut, onAuthStateChanged,
} from "https://www.gstatic.com/firebasejs/13.4.0/firebase-auth.js";
import {
  getFunctions, httpsCallable,
} from "https://www.gstatic.com/firebasejs/13.4.0/firebase-functions.js";
import { firebaseConfig } from "./config.js";
import { createManualGrantControl } from "./manual-grants.js";
import { createDeletionReviewPanel } from "./deletion-reviews.js";

const $ = id => document.getElementById(id);
const authStatus = $("auth-status");
const signin = $("signin");
const signout = $("signout");
const overview = $("overview");
const refresh = $("refresh");
const overviewStatus = $("overview-status");
const memberList = $("member-list");
const memberStatus = $("members-status");
const memberNext = $("members-next");
const memberPrev = $("members-prev");
const memberPage = $("members-page");
const memberRefresh = $("members-refresh");
let authorizedUser = null;
let auth = null;
let getOverview = null;
let getMembers = null;
let manualGrantCalls = null;
let deletionPanel = null;
let memberTokens = [null];
let currentMemberPage = 0;
let memberRequestId = 0;

function authMessage(message) {
  authStatus.textContent = message;
}
function resetDashboard() {
  overview.hidden = true;
  authorizedUser = null;
  memberRequestId++;
  deletionPanel?.clear();
  memberTokens = [null];
  currentMemberPage = 0;
  memberList.replaceChildren();
  memberStatus.textContent = "관리자 인증 후 회원 목록을 확인합니다.";
  memberPage.textContent = "1페이지";
  memberPrev.disabled = true;
  memberNext.disabled = true;
  memberRefresh.disabled = true;
  for (const id of ["live-billing", "license-billing", "pending-deletion", "manual-review", "purge-review"]) {
    $(id).textContent = "—";
  }
  $("checked-at").textContent = "";
}
function validateConfig(config) {
  return config && typeof config.apiKey === "string" && config.apiKey.trim() !== "" &&
    typeof config.appId === "string" && config.appId.trim() !== "" &&
    config.projectId === "gallipick-production" &&
    typeof config.authDomain === "string" &&
    /^[-a-z0-9]+\.firebaseapp\.com$/.test(config.authDomain);
}

const koNumber = new Intl.NumberFormat("ko-KR");
const koDateTime = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul", dateStyle: "medium", timeStyle: "short",
});
function safeText(value, fallback = "확인 불가") {
  return typeof value === "string" && value.trim() ? value : fallback;
}
function formatDateTime(ms) {
  return Number.isSafeInteger(ms) && ms > 0 ? koDateTime.format(new Date(ms)) : "미확인";
}
function formatBytes(bytes) {
  return Number.isSafeInteger(bytes) && bytes >= 0
    ? koNumber.format(Math.round(bytes / 100_000_000) / 10) + " GB" : "확인 불가";
}
function element(tag, text, className = "") {
  const node = document.createElement(tag);
  if (className) node.className = className;
  node.textContent = text;
  return node;
}
function appendPlan(container, plan) {
  const section = element("section", "", "member-plan");
  const mode = plan.mode === "live" ? "실결제" : "라이선스 테스트";
  section.append(element("strong", mode));
  const labels = {
    VERIFIED_CLOUD: "서버 검증 Cloud 이용권",
    VERIFIED_AD_FREE: "서버 검증 광고 제거",
    NO_VERIFIED_ACTIVE_PRODUCT: "현재 검증된 유효 상품 없음",
  };
  section.append(element("p", labels[plan.subscriptionStatus] ?? "구독 상태 확인 불가"));
  if (plan.productId) {
    section.append(element("p", "상품: " + plan.productId + " (" + safeText(plan.basePlanId) + ")"));
  }
  section.append(element("p", "구독 권리 종료: " + formatDateTime(plan.paidThroughAtMs)));
  section.append(element("p", "서버 마지막 확인: " + formatDateTime(plan.lastVerifiedAtMs)));
  section.append(element("p", "저장공간: " + formatBytes(plan.usedBytes)
    + " / 현재 할당량 " + formatBytes(plan.quotaBytes)));
  const lifecycleLabels = {
    PAID: "검증된 이용 기간",
    READ_ONLY_RECOVERY: "복원 가능·업로드 중단",
    LOCKED_AWAITING_PURGE: "접근 종료·삭제 검토 예정",
    PURGE_DUE: "삭제 검토 시점 도래",
    CLOUD_ACCESS_SUSPENDED: "클라우드 접근 제한",
    ACCOUNT_DELETION_REQUESTED: "회원 탈퇴 요청",
  };
  if (plan.lifecycleState && plan.lifecycleState !== "NO_VERIFIED_CLOUD_EXPIRY") {
    section.append(element("p", "클라우드 상태: " +
      (lifecycleLabels[plan.lifecycleState] ?? "확인 필요")));
  }
  if (plan.recoveryEndsAtMs != null) {
    section.append(element("p", "복원 기간 종료: " + formatDateTime(plan.recoveryEndsAtMs)));
  }
  if (plan.deletionDueAtMs != null) {
    section.append(element("p", "삭제 검토 예정: " + formatDateTime(plan.deletionDueAtMs)));
  }
  container.append(section);
}
function renderMember(member) {
  const item = element("li", "", "member-entry");
  const header = element("div", "", "member-heading");
  header.append(element("h3", safeText(member.emailMasked, "이메일 미등록")));
  header.append(element("span", "회원 식별자 " + member.ownerId.slice(0, 12) + "…", "member-ref"));
  item.append(header);
  item.append(element("p", "가입: " + formatDateTime(member.createdAtMs) +
    " · 최근 로그인: " + formatDateTime(member.lastSignInAtMs) +
    (member.disabled ? " · 계정 비활성화" : ""), "member-meta"));
  if (member.exitStatus !== "NONE") {
    const [scope, state] = safeText(member.exitStatus).split(":");
    const subject = scope === "ACCOUNT" ? "회원 계정" : "클라우드";
    const stateLabels = {
      REQUESTED: "접수", IN_PROGRESS: "처리 중", RUNNING: "처리 중",
      RETRY: "재시도", MANUAL_REVIEW: "운영자 확인 필요",
      COMPLETED: "삭제 완료", RESUMED: "백업 재활성화",
    };
    item.append(element("p", "삭제 요청: " + subject + " · " +
      (stateLabels[state] ?? "확인 필요"), "member-meta"));
  }
  const plans = element("div", "", "member-plans");
  for (const plan of member.plans) appendPlan(plans, plan);
  item.append(plans);
  if (manualGrantCalls) item.append(createManualGrantControl(member, manualGrantCalls));
  return item;
}
function validMemberResponse(data) {
  return data && data.pageSize === 15 && Array.isArray(data.members) &&
    data.members.length <= 15 &&
    (data.nextPageToken === null || (typeof data.nextPageToken === "string"
      && data.nextPageToken.length > 0 && data.nextPageToken.length <= 2048)) &&
    data.members.every(member => /^[a-f0-9]{64}$/.test(member?.ownerId) &&
      Array.isArray(member.plans) && member.plans.length === 2 &&
      member.plans[0]?.mode === "live" && member.plans[1]?.mode === "license-test");
}
async function loadMembers(pageIndex) {
  if (!authorizedUser || !getMembers || pageIndex < 0 ||
      pageIndex >= memberTokens.length || memberTokens[pageIndex] === undefined) return;
  const session = authorizedUser;
  const requestId = ++memberRequestId;
  memberPrev.disabled = true;
  memberNext.disabled = true;
  memberRefresh.disabled = true;
  memberStatus.textContent = "회원별 구독 및 용량 기록을 확인하는 중입니다.";
  memberList.replaceChildren();
  try {
    const token = memberTokens[pageIndex];
    const response = await getMembers(token == null ? {} : { pageToken: token });
    if (authorizedUser !== session || requestId !== memberRequestId) return;
    const data = response.data;
    if (!validMemberResponse(data)) throw new Error("invalid-member-response");
    for (const member of data.members) memberList.append(renderMember(member));
    currentMemberPage = pageIndex;
    if (data.nextPageToken === null) memberTokens.length = pageIndex + 1;
    else memberTokens[pageIndex + 1] = data.nextPageToken;
    memberPrev.disabled = pageIndex === 0;
    memberNext.disabled = data.nextPageToken === null;
    memberPage.textContent = (pageIndex + 1) + "페이지";
    memberStatus.textContent = data.members.length
      ? koNumber.format(data.members.length) + "명 조회 완료 · 실시간 결제 갱신 여부는 별도 확인 필요"
      : "조회 가능한 회원이 없습니다.";
  } catch (error) {
    if (authorizedUser !== session || requestId !== memberRequestId) return;
    if (error?.code === "functions/permission-denied") {
      resetDashboard();
      authMessage("회원별 정보 조회 권한이 없습니다.");
    } else {
      memberStatus.textContent = "회원 목록을 확인하지 못했습니다. 네트워크와 서버 기록을 확인한 후 새로고침해 주세요.";
      memberPrev.disabled = currentMemberPage === 0;
    }
  } finally {
    if (authorizedUser === session && requestId === memberRequestId) memberRefresh.disabled = false;
  }
}

async function updateOverview() {
  if (!authorizedUser || !getOverview) return;
  const session = authorizedUser;
  refresh.disabled = true;
  overviewStatus.textContent = "운영 통계를 확인하는 중입니다.";
  try {
    const response = await getOverview({});
    if (authorizedUser !== session) return;
    const data = response.data;
    const fields = {
      "live-billing": "liveBillingRecords",
      "license-billing": "licenseBillingRecords",
      "pending-deletion": "pendingDeletionRequests",
      "manual-review": "manualReviewDeletionRequests",
      "purge-review": "purgeCandidatesPendingPlayRecheck",
    };
    for (const key of Object.values(fields)) {
      if (!Number.isSafeInteger(data?.[key]) || data[key] < 0) throw new Error("invalid-response");
    }
    if (!Number.isSafeInteger(data.checkedAtMs) || data.checkedAtMs <= 0) throw new Error("invalid-date");
    const formatter = new Intl.NumberFormat("ko-KR");
    for (const [id, key] of Object.entries(fields)) $(id).textContent = formatter.format(data[key]);
    $("checked-at").textContent = new Intl.DateTimeFormat("ko-KR", {
      timeZone: "Asia/Seoul", dateStyle: "medium", timeStyle: "short",
    }).format(new Date(data.checkedAtMs)) + " 기준";
    overviewStatus.textContent = "서버 집계 확인 완료";
  } catch (error) {
    if (error?.code === "functions/permission-denied" ||
        error?.code === "functions/failed-precondition") {
      resetDashboard();
      authMessage("서버에서 관리자 접근 권한을 승인하지 않았습니다.");
    } else {
      overviewStatus.textContent = "통계를 확인하지 못했습니다. 권한이나 서버 설정을 확인해 주세요.";
    }
  } finally {
    refresh.disabled = false;
  }
}

if (!validateConfig(firebaseConfig)) {
  authMessage("관리자 Firebase 웹 앱 연결 정보가 설정되지 않았습니다. 배포 설정을 확인해 주세요.");
} else {
  const firebaseApp = initializeApp(firebaseConfig);
  auth = getAuth(firebaseApp);
  getOverview = httpsCallable(getFunctions(firebaseApp, "asia-northeast3"), "getAdminOverview");
  getMembers = httpsCallable(getFunctions(firebaseApp, "asia-northeast3"), "getAdminMembers");
  const cloudFunctions = getFunctions(firebaseApp, "asia-northeast3");
  const readGrant = httpsCallable(cloudFunctions, "getAdminManualGrant");
  const issueGrant = httpsCallable(cloudFunctions, "issueAdminManualGrant");
  const revokeGrant = httpsCallable(cloudFunctions, "revokeAdminManualGrant");
  manualGrantCalls = {
    currentUser: () => authorizedUser && auth.currentUser?.uid === authorizedUser.uid,
    read: data => readGrant(data),
    issue: data => issueGrant(data),
    revoke: data => revokeGrant(data),
    async reauthenticate() {
      const user = auth.currentUser;
      if (!user || user.uid !== authorizedUser?.uid) throw new Error("Admin identity changed");
      const provider = new GoogleAuthProvider();
      provider.setCustomParameters({ prompt: "select_account" });
      await reauthenticateWithPopup(user, provider);
      if (auth.currentUser?.uid !== user.uid || authorizedUser !== user) {
        throw new Error("Admin identity changed during reauthentication");
      }
      await user.getIdToken(true);
    },
  };
  const adminListDeletions = httpsCallable(cloudFunctions, "getAdminDeletionReviews");
  const adminPreviewPurge = httpsCallable(cloudFunctions, "previewAdminExpiryPurge");
  const adminRetryDeletion = httpsCallable(cloudFunctions, "retryAdminFailedDeletion");
  deletionPanel = createDeletionReviewPanel(document.getElementById("deletion-admin"), {
    currentUser: manualGrantCalls.currentUser,
    reauthenticate: () => manualGrantCalls.reauthenticate(),
    list: data => adminListDeletions(data),
    preview: data => adminPreviewPurge(data),
    retry: data => adminRetryDeletion(data),
  });
  signin.hidden = false;

  signin.addEventListener("click", async () => {
    signin.disabled = true;
    authMessage("Google 인증 화면을 여는 중입니다.");
    try {
      await setPersistence(auth, browserSessionPersistence);
      const provider = new GoogleAuthProvider();
      provider.setCustomParameters({ prompt: "select_account" });
      await signInWithPopup(auth, provider);
    } catch {
      authMessage("Google 계정 인증에 실패했습니다. 계정 또는 브라우저 설정을 확인해 주세요.");
    } finally {
      signin.disabled = false;
    }
  });
  signout.addEventListener("click", async () => {
    await signOut(auth);
  });
  refresh.addEventListener("click", async () => {
    await updateOverview();
    if (authorizedUser) await loadMembers(currentMemberPage);
    if (authorizedUser) await deletionPanel?.refresh();
  });
  memberRefresh.addEventListener("click", async () => {
    memberTokens = [null];
    await loadMembers(0);
  });
  memberPrev.addEventListener("click", () => loadMembers(currentMemberPage - 1));
  memberNext.addEventListener("click", () => loadMembers(currentMemberPage + 1));

  onAuthStateChanged(auth, async user => {
    resetDashboard();
    if (!user) {
      authMessage("관리자 Google 계정으로 로그인해 주세요.");
      signin.hidden = false;
      signout.hidden = true;
      return;
    }
    signin.hidden = true;
    signout.hidden = false;
    authMessage("관리자 권한을 검증하는 중입니다.");
    try {
      const token = await user.getIdTokenResult(true);
      if (auth.currentUser?.uid !== user.uid ||
          token.claims.gallipickAdmin !== true) {
        authMessage("이 Google 계정에는 GalliPick 관리자 권한이 없습니다.");
        return;
      }
      authorizedUser = user;
      overview.hidden = false;
      authMessage("Google 인증 완료. 서버의 관리자 접근 권한을 확인합니다.");
      await updateOverview();
      if (authorizedUser === user && overviewStatus.textContent === "서버 집계 확인 완료") {
        authMessage("관리자 접근 승인");
        await loadMembers(0);
        await deletionPanel?.refresh();
      }
    } catch {
      resetDashboard();
      authMessage("관리자 권한 검증에 실패했습니다. 다시 로그인해 주세요.");
    }
  });
}

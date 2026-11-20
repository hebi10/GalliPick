import { initializeApp } from "https://www.gstatic.com/firebasejs/13.4.0/firebase-app.js";
import {
  getAuth, GoogleAuthProvider, browserSessionPersistence, setPersistence,
  signInWithPopup, signOut, onAuthStateChanged,
} from "https://www.gstatic.com/firebasejs/13.4.0/firebase-auth.js";
import {
  getFunctions, httpsCallable,
} from "https://www.gstatic.com/firebasejs/13.4.0/firebase-functions.js";
import { firebaseConfig } from "./config.js";

const $ = id => document.getElementById(id);
const authStatus = $("auth-status");
const signin = $("signin");
const signout = $("signout");
const overview = $("overview");
const refresh = $("refresh");
const overviewStatus = $("overview-status");
let authorizedUser = null;
let auth = null;
let getOverview = null;

function authMessage(message) {
  authStatus.textContent = message;
}
function resetDashboard() {
  overview.hidden = true;
  authorizedUser = null;
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
  refresh.addEventListener("click", updateOverview);

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
      }
    } catch {
      resetDashboard();
      authMessage("관리자 권한 검증에 실패했습니다. 다시 로그인해 주세요.");
    }
  });
}

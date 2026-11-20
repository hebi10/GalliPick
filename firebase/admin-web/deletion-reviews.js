// Destructive actions are intentionally absent. The bulk control is dry-run only.
function textNode(tag, value, className = "") {
  const node = document.createElement(tag);
  node.textContent = String(value);
  if (className) node.className = className;
  return node;
}

const time = value => Number.isSafeInteger(value) && value > 0
  ? new Intl.DateTimeFormat("ko-KR", {
      timeZone: "Asia/Seoul", dateStyle: "medium", timeStyle: "short",
    }).format(new Date(value))
  : "미확인";

const stateLabels = {
  REQUESTED: "사용자 요청 접수",
  RUNNING: "삭제 처리 중",
  IN_PROGRESS: "삭제 처리 중",
  RETRY: "서버 재시도 대기",
  MANUAL_REVIEW: "운영자 검토 필요",
  NEEDS_PLAY_RECHECK: "Google Play 최신 결제 재확인 필요",
  STALE_BILLING_REVISION: "결제 기록 변경·검토 보류",
  ACTIVE_ENTITLEMENT: "유효 결제 기록 발견·삭제 보류",
  ACTIVE_MANUAL_ACCESS: "수동 Cloud 이용권 유효·삭제 보류",
  EXISTING_DELETION: "기존 삭제 요청 우선·검토 보류",
  INCOMPLETE_EVIDENCE: "증빙 부족·검토 보류",
  NOT_DUE: "보존 기한 미도래",
};
const statusName = key => stateLabels[key] ?? "검증 또는 관리자 검토 필요";

export function createDeletionReviewPanel(root, api) {
  const summary = root.querySelector("#deletion-review-status");
  const exitList = root.querySelector("#deletion-review-list");
  const dueList = root.querySelector("#purge-review-list");
  const dryRunList = root.querySelector("#purge-preview-list");
  const dryRunStatus = root.querySelector("#purge-preview-status");
  const refreshButton = root.querySelector("#deletion-review-refresh");
  const previewButton = root.querySelector("#purge-review-preview");
  let generation = 0;
  let loading = false;

  function clear() {
    generation++;
    loading = false;
    exitList.replaceChildren();
    dueList.replaceChildren();
    dryRunList.replaceChildren();
    summary.textContent = "관리자 인증 후 삭제 요청 내역을 확인합니다.";
    dryRunStatus.textContent = "미리보기는 실제 삭제 명령이 아닙니다.";
    refreshButton.disabled = true;
    previewButton.disabled = true;
  }

  function row(label, subtext) {
    const li = textNode("li", "", "deletion-review-item");
    li.append(textNode("strong", label));
    li.append(textNode("p", subtext));
    return li;
  }

  function appendRetryControl(item, entry) {
    const details = textNode("details", "", "deletion-review-retry");
    details.append(textNode("summary", "실패한 기존 삭제 요청 재시도"));
    const reason = document.createElement("textarea");
    reason.rows = 3;
    reason.minLength = 12;
    reason.maxLength = 280;
    reason.placeholder = "재시도 사유와 확인 내용 (12~280자)";
    const button = textNode("button", "재인증 후 재시도 요청", "button");
    button.type = "button";
    const note = textNode("p",
      "이미 접수된 사용자 요청만 재시도합니다. 서버 워커가 활성일 때만 실행되며 완료를 보장하지 않습니다.");
    const state = textNode("p", "");
    state.setAttribute("role", "status");
    details.append(reason, note, button, state);
    item.append(details);
    button.addEventListener("click", async () => {
      const supplied = reason.value.trim();
      if (!api.currentUser() || supplied.length < 12 || supplied.length > 280) {
        state.textContent = "관리자 로그인과 재시도 사유 12~280자를 확인해 주세요.";
        return;
      }
      const ownerShort = entry.ownerId?.slice(0, 12);
      if (!window.confirm("기존 사용자 삭제 요청 " + ownerShort +
        "…의 실패 작업만 다시 예약합니다.\n새 삭제 요청은 만들지 않습니다. 진행하시겠습니까?")) return;
      button.disabled = true;
      state.textContent = "Google 관리자 재인증을 확인하는 중입니다.";
      const localGeneration = generation;
      try {
        await api.reauthenticate();
        if (!api.currentUser() || localGeneration !== generation) return;
        state.textContent = "서버에 기존 작업 재시도를 요청하는 중입니다.";
        const result = await api.retry({
          ownerId: entry.ownerId, deletionRequestId: entry.deletionRequestId,
          idempotencyKey: crypto.randomUUID().replaceAll("-", ""),
          reason: supplied, confirmation: "RETRY_EXISTING_DELETION_ONLY",
        });
        if (localGeneration !== generation || !api.currentUser()) return;
        if (result?.data?.accepted !== true || result.data.status !== "RETRY") {
          throw new Error("invalid-retry-result");
        }
        state.textContent = "재시도 요청을 서버에 예약했습니다. 실제 삭제 완료 상태를 별도로 확인해 주세요.";
        await refresh();
      } catch {
        if (localGeneration === generation) {
          state.textContent = "재시도가 접수되지 않았습니다. 서버 설정 또는 작업 상태를 확인해 주세요.";
        }
      } finally {
        if (localGeneration === generation) button.disabled = false;
      }
    });
  }

  async function refresh() {
    if (!api.currentUser() || loading) return;
    const localGeneration = ++generation;
    loading = true;
    refreshButton.disabled = true;
    previewButton.disabled = true;
    summary.textContent = "삭제 요청과 Play 재검토 기록을 조회하고 있습니다.";
    exitList.replaceChildren();
    dueList.replaceChildren();
    dryRunList.replaceChildren();
    try {
      const response = await api.list({});
      if (localGeneration !== generation || !api.currentUser()) return;
      const data = response.data;
      if (!Array.isArray(data?.deletionRequests) || !Array.isArray(data?.purgeReviews) ||
          data.deletionRequests.length > 20 || data.purgeReviews.length > 20 ||
          data.displayLimit !== 20) throw new Error("invalid-admin-list");
      for (const entry of data.deletionRequests) {
        const short = /^[a-f0-9]{64}$/.test(entry?.ownerId ?? "")
          ? entry.ownerId.slice(0, 12) + "…" : "소유자 확인 필요";
        const li = row("삭제 요청 · " + short,
          "범위: " + (entry.scope === "ACCOUNT" ? "회원 전체" : "클라우드 데이터") +
          " · " + statusName(entry.status) +
          " · 접수 " + time(entry.requestedAtMs) +
          " · 처리 목표 " + time(entry.deadlineAtMs));
        if (entry.status === "MANUAL_REVIEW" &&
            /^[a-f0-9]{64}$/.test(entry.ownerId ?? "") &&
            /^[a-f0-9]{32}$/.test(entry.deletionRequestId ?? "")) {
          appendRetryControl(li, entry);
        }
        exitList.append(li);
      }
      for (const entry of data.purgeReviews) {
        const short = /^[a-f0-9]{64}$/.test(entry?.ownerId ?? "")
          ? entry.ownerId.slice(0, 12) + "…" : "소유자 확인 필요";
        dueList.append(row("구독 만료 후보 · " + short,
          "환경: " + (entry.scope === "gallipickProductionBilling" ? "운영 결제" : "라이선스 시험") +
          " · Play 재확인 필요 · 만료 기록 " + time(entry.expiresAtMs) +
          " · 삭제 검토 예정 " + time(entry.deletionDueAtMs)));
      }
      if (!exitList.children.length) exitList.append(row("대기 요청 없음", "조회된 처리 대기·오류 작업이 없습니다."));
      if (!dueList.children.length) dueList.append(row("검토 기록 없음", "최근 조회 범위 내 검토 후보가 없습니다."));
      summary.textContent = "삭제 요청 " + data.deletionRequests.length +
        "건, 재검증 후보 " + data.purgeReviews.length +
        "건 조회 · 각 목록 최대 20건" +
        (data.moreDeletionRequests || data.morePurgeReviews ? " · 추가 기록 존재" : "");
    } catch {
      if (localGeneration === generation) {
        summary.textContent = "삭제 관리 현황을 조회하지 못했습니다. 관리자 권한과 서버 상태를 확인해 주세요.";
      }
    } finally {
      if (localGeneration === generation) {
        loading = false;
        refreshButton.disabled = false;
        previewButton.disabled = false;
      }
    }
  }

  async function preview() {
    if (!api.currentUser() || loading) return;
    const localGeneration = ++generation;
    loading = true;
    previewButton.disabled = true;
    refreshButton.disabled = true;
    dryRunList.replaceChildren();
    dryRunStatus.textContent = "현재 구독 권한과 삭제 후보를 재검토 중입니다. 삭제 작업은 수행하지 않습니다.";
    try {
      const response = await api.preview({});
      if (localGeneration !== generation || !api.currentUser()) return;
      const data = response.data;
      if (data?.dryRunOnly !== true || data.deletionExecuted !== false ||
          !Array.isArray(data.items) || data.items.length > 20 ||
          data.items.some(item => item.deletionAllowed !== false)) {
        throw new Error("unexpected-deletion-preview");
      }
      for (const item of data.items) {
        const id = /^[a-f0-9]{64}$/.test(item.ownerId ?? "")
          ? item.ownerId.slice(0, 12) + "…" : "증빙 확인 필요";
        dryRunList.append(row("후보 " + id,
          (item.mode === "live" ? "운영 결제" : "라이선스 시험") +
          " · " + statusName(item.status) + " · 자동 삭제 불가"));
      }
      if (!dryRunList.children.length) {
        dryRunList.append(row("검토 대상 없음", "조회된 범위 내 후보가 없습니다."));
      }
      dryRunStatus.textContent = "사전 검토 " + data.items.length +
        "건 완료 · 실제 삭제 0건 · Play 최신 결제 재검증 전 삭제 실행 불가" +
        (data.hasMore ? " · 추가 검토 기록 있음" : "");
    } catch {
      if (localGeneration === generation) {
        dryRunStatus.textContent = "사전 검토에 실패했습니다. 어떤 데이터도 삭제하지 않았습니다.";
      }
    } finally {
      if (localGeneration === generation) {
        loading = false;
        refreshButton.disabled = false;
        previewButton.disabled = false;
      }
    }
  }

  refreshButton.addEventListener("click", refresh);
  previewButton.addEventListener("click", preview);
  clear();
  return { refresh, clear };
}

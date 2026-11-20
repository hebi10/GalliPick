// All administrative deletion operations are authenticated server callables.
// "Batch preview" intentionally has no execution companion.
const $ = id => document.getElementById(id);
const VIEW_STATUS = {
  REQUESTED: "접수",
  IN_PROGRESS: "처리 중",
  RUNNING: "처리 중",
  RETRY: "재시도",
  MANUAL_REVIEW: "관리자 점검 필요",
  COMPLETED: "삭제 완료",
  RESUMED: "Cloud 재활성화",
  NEEDS_PLAY_RECHECK: "Google Play 결제 상태 재검증 필요",
};
const DECISIONS = {
  NEEDS_PLAY_RECHECK: "Play 현재 상태 미검증 · 삭제 불가",
  INDIVIDUAL_RETRY_ONLY: "사용자 기존 요청에 한해 관리자 재시도 가능",
  USER_REQUEST_IN_PROGRESS: "사용자 삭제 요청 처리 중",
  ALREADY_PROCESSED: "이미 처리된 요청",
  NOT_FOUND: "기록 없음 · 삭제 불가",
};
const dateFormat = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul", dateStyle: "medium", timeStyle: "short",
});
function node(tag, value, klass = "") {
  const el = document.createElement(tag);
  if (klass) el.className = klass;
  if (value != null) el.textContent = value;
  return el;
}
function date(ms) {
  return Number.isSafeInteger(ms) && ms > 0 ? dateFormat.format(new Date(ms)) : "미확인";
}
function validId(id, view) {
  return view === "EXIT" ? /^[a-f0-9]{64}$/.test(id)
    : /^gallipickProduction(?:License)?Billing-[a-f0-9]{64}$/.test(id);
}
function validRow(row, view) {
  if (!row || !validId(row.id, view)) return false;
  if (view === "EXIT") {
    return ["ACCOUNT", "CLOUD"].includes(row.scope) &&
      Object.hasOwn(VIEW_STATUS, row.status) &&
      Number.isSafeInteger(row.requestedAtMs) &&
      /^[a-f0-9]{32}$/.test(row.requestId);
  }
  return row.status === "NEEDS_PLAY_RECHECK" &&
    Number.isSafeInteger(row.deletionDueAtMs) &&
    Number.isSafeInteger(row.verifiedExpiryAtMs);
}

export function createDeletionReviewPanel(api) {
  const status = $("deletion-status");
  const list = $("deletion-list");
  const view = $("deletion-view");
  const refresh = $("deletion-refresh");
  const prev = $("deletion-prev");
  const next = $("deletion-next");
  const page = $("deletion-page");
  const preview = $("deletion-preview");
  const dryRun = $("deletion-dry-run");
  let pages = [null];
  let currentPage = 0;
  let shownRows = [];
  let selected = new Set();
  let sequence = 0;
  let busy = false;

  function lock() {
    const active = Boolean(api.currentUser());
    view.disabled = busy || !active;
    refresh.disabled = busy || !active;
    prev.disabled = busy || !active || currentPage === 0;
    next.disabled = busy || !active || !pages[currentPage + 1];
    preview.disabled = busy || !active || selected.size === 0;
  }
  function reset(message = "관리자 인증 후 삭제 요청 현황을 확인합니다.") {
    sequence++;
    pages = [null];
    currentPage = 0;
    shownRows = [];
    selected = new Set();
    busy = false;
    list.replaceChildren();
    dryRun.replaceChildren();
    page.textContent = "1페이지";
    status.textContent = message;
    lock();
  }
  function select(id, checked) {
    if (checked && selected.size >= 10) {
      status.textContent = "사전 검토는 한 번에 10건까지 선택할 수 있습니다.";
      return false;
    }
    if (checked) selected.add(id);
    else selected.delete(id);
    lock();
    return true;
  }
  function rowNode(row, selectedView) {
    const item = node("li", null, "deletion-entry");
    const header = node("div", null, "deletion-entry-heading");
    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.setAttribute("aria-label", "사전 검토 대상 선택: " + row.id.slice(0, 12));
    checkbox.addEventListener("change", () => {
      if (!select(row.id, checkbox.checked)) checkbox.checked = false;
    });
    header.append(checkbox);
    header.append(node("strong", "계정 " + (selectedView === "EXIT"
      ? row.id.slice(0, 12) : row.ownerId?.slice(0, 12)) + "…"));
    header.append(node("span", VIEW_STATUS[row.status] ?? "확인 필요", "deletion-state"));
    item.append(header);
    if (selectedView === "PURGE") {
      item.append(node("p", "만료일 " + date(row.verifiedExpiryAtMs) +
        " · 검토 기준 " + date(row.deletionDueAtMs)));
      item.append(node("p", "현재 Google Play 구매 상태 재확인 전까지 파일 삭제 불가", "hint"));
      return item;
    }
    item.append(node("p", (row.scope === "ACCOUNT" ? "계정 전체" : "Cloud 데이터") +
      " · 접수 " + date(row.requestedAtMs) +
      " · 처리 목표 " + date(row.deadlineAtMs)));
    if (row.status === "MANUAL_REVIEW" && row.stage) {
      const wrapper = node("div", null, "deletion-retry");
      wrapper.append(node("p", "사용자가 이미 요청한 삭제 작업의 실패 지점만 재시도합니다. 새 삭제 요청이나 범위 확대는 할 수 없습니다.", "hint"));
      const reason = document.createElement("textarea");
      reason.rows = 2;
      reason.maxLength = 280;
      reason.placeholder = "오류 조사 및 재시도 사유 (10~280자)";
      reason.setAttribute("aria-label", "재시도 사유");
      const button = node("button", "해당 요청 재시도", "button");
      button.type = "button";
      button.addEventListener("click", async () => {
        const text = reason.value.trim();
        if (busy || text.length < 10 || text.length > 280 || !api.currentUser()) {
          status.textContent = "재시도 사유를 10~280자로 입력하고 관리자 권한을 확인해 주세요.";
          return;
        }
        if (!window.confirm(
          "요청 ID " + row.requestId.slice(0, 10) + "…\n" +
          "원래 삭제 범위: " + row.scope + "\n" +
          "이전에 실패한 사용자 요청에 한해 재시도를 예약합니다. 계속하시겠습니까?"
        )) return;
        const started = ++sequence;
        busy = true;
        lock();
        status.textContent = "관리자 Google 계정 재인증 중입니다.";
        try {
          await api.reauthenticate();
          if (started !== sequence || !api.currentUser()) return;
          const response = await api.retry({
            ownerId: row.id,
            originalRequestId: row.requestId,
            actionId: crypto.randomUUID().replaceAll("-", ""),
            scope: row.scope,
            expectedStage: row.stage,
            reason: text,
            confirmation: "RETRY_EXISTING_USER_DELETION",
          });
          if (started !== sequence || !api.currentUser()) return;
          if (response.data?.scheduled !== true) throw new Error("The retry was not accepted");
          status.textContent = "기존 삭제 요청의 재시도를 예약했습니다. 서버의 완료 확인 전에는 삭제 완료로 간주하지 않습니다.";
          busy = false;
          await load(currentPage);
        } catch {
          if (started !== sequence) return;
          status.textContent = "서버에서 재시도를 예약하지 못했습니다. 운영 스위치·권한과 상태를 확인해 주세요.";
        } finally {
          if (started === sequence) { busy = false; lock(); }
        }
      });
      wrapper.append(reason, button);
      item.append(wrapper);
    }
    return item;
  }
  async function load(pageIndex = 0) {
    if (!api.currentUser() || pageIndex < 0 || pageIndex >= pages.length) return;
    const selectedView = view.value;
    const started = ++sequence;
    busy = true;
    status.textContent = "삭제 요청 및 검토 기록을 읽는 중입니다.";
    selected.clear();
    list.replaceChildren();
    dryRun.replaceChildren();
    lock();
    try {
      const token = pages[pageIndex];
      const response = await api.list(token ? { view: selectedView, cursor: token } : { view: selectedView });
      if (started !== sequence || !api.currentUser() || selectedView !== view.value) return;
      const data = response.data;
      if (!data || data.view !== selectedView || data.pageSize !== 15 ||
          !Array.isArray(data.rows) || data.rows.length > 15 ||
          data.rows.some(row => !validRow(row, selectedView)) ||
          (data.nextCursor !== null && !validId(data.nextCursor, selectedView))) {
        throw new Error("Invalid deletion review response");
      }
      shownRows = data.rows;
      currentPage = pageIndex;
      if (data.nextCursor === null) pages.length = pageIndex + 1;
      else pages[pageIndex + 1] = data.nextCursor;
      for (const item of shownRows) list.append(rowNode(item, selectedView));
      page.textContent = (pageIndex + 1) + "페이지";
      status.textContent = shownRows.length + "건 조회했습니다. 조회 및 사전 검토는 파일 삭제를 실행하지 않습니다.";
    } catch {
      if (started !== sequence) return;
      status.textContent = "삭제 요청 기록을 확인하지 못했습니다. 관리자 접근 권한과 서버 상태를 확인해 주세요.";
    } finally {
      if (started === sequence) { busy = false; lock(); }
    }
  }
  async function previewBatch() {
    if (!api.currentUser() || busy || selected.size === 0 || selected.size > 10) return;
    const selectedView = view.value;
    const started = ++sequence;
    const ids = [...selected];
    busy = true;
    lock();
    dryRun.replaceChildren();
    status.textContent = "선택한 요청의 사전 검토를 수행 중입니다. 삭제 실행은 하지 않습니다.";
    try {
      const response = await api.preview({ view: selectedView, ids });
      if (started !== sequence || !api.currentUser()) return;
      const result = response.data;
      if (result?.canExecute !== false || result.serverDeletesPerformed !== 0 ||
          result.view !== selectedView || !Array.isArray(result.rows) ||
          result.rows.length !== ids.length ||
          result.rows.some(item => !ids.includes(item.id) || !Object.hasOwn(DECISIONS, item.decision))) {
        throw new Error("Invalid non-destructive preview result");
      }
      dryRun.append(node("strong", "사전 검토 결과 · 실행된 삭제 0건"));
      for (const entry of result.rows) {
        dryRun.append(node("p", entry.id.slice(0, 12) + "… " + DECISIONS[entry.decision]));
      }
      status.textContent = "사전 검토만 완료했습니다. 서버 데이터와 사용자 파일은 변경하지 않았습니다.";
    } catch {
      if (started !== sequence) return;
      status.textContent = "사전 검토 실패. 재시도 전에 서버 기록과 권한을 확인해 주세요.";
    } finally {
      if (started === sequence) { busy = false; lock(); }
    }
  }

  view.addEventListener("change", () => { reset("조회할 검토 유형을 변경했습니다."); void load(0); });
  refresh.addEventListener("click", () => { reset("삭제 기록 새로고침 중입니다."); void load(0); });
  prev.addEventListener("click", () => void load(currentPage - 1));
  next.addEventListener("click", () => void load(currentPage + 1));
  preview.addEventListener("click", () => void previewBatch());
  reset();
  return { load, reset };
}

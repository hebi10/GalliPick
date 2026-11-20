// The admin UI never receives purchase tokens or Firebase Auth UIDs.
const tiers = {
  CLOUD_10GB: "Cloud 10 GB",
  CLOUD_50GB: "Cloud 50 GB",
  CLOUD_100GB: "Cloud 100 GB",
  AD_FREE: "광고 제거",
};

function node(tag, value, className = "") {
  const el = document.createElement(tag);
  if (value !== null && value !== undefined) el.textContent = value;
  if (className) el.className = className;
  return el;
}

function field(title, input) {
  const label = node("label", title, "manual-field");
  label.append(input);
  return label;
}

function formatDate(ms) {
  return Number.isSafeInteger(ms) && ms > 0
    ? new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", dateStyle: "medium", timeStyle: "short" })
      .format(new Date(ms)) : "미확인";
}

export function createManualGrantControl(member, api) {
  const details = node("details", null, "manual-grant");
  const title = node("summary", "수동 이용권 관리 · Play 구독과 별도");
  details.append(title);

  const form = node("div", null, "manual-form");
  const status = node("p", "관리자 권한 확인 후 이용권 내역을 조회합니다.");
  status.setAttribute("role", "status");
  status.setAttribute("aria-live", "polite");

  const environment = node("select");
  for (const [value, label] of [["live", "실결제 운영"], ["license-test", "라이선스 시험"]]) {
    const option = node("option", label);
    option.value = value;
    environment.append(option);
  }
  const kind = node("select");
  for (const [value, label] of Object.entries(tiers)) {
    const option = node("option", label);
    option.value = value;
    kind.append(option);
  }
  const days = document.createElement("input");
  days.type = "number";
  days.min = "1";
  days.max = "90";
  days.step = "1";
  days.value = "7";
  days.required = true;
  const reason = document.createElement("textarea");
  reason.rows = 3;
  reason.minLength = 10;
  reason.maxLength = 280;
  reason.required = true;
  reason.placeholder = "지급·회수 사유 (10~280자)";
  const confirm = document.createElement("input");
  confirm.type = "checkbox";
  const confirmLabel = field("대상 계정·환경·상품·기한과 사유를 확인했습니다.", confirm);
  confirmLabel.classList.add("manual-confirm");

  const buttons = node("div", null, "manual-buttons");
  const issue = node("button", "이용권 지급", "button");
  const revoke = node("button", "이용권 회수", "button");
  const refresh = node("button", "이력 다시 확인", "button");
  issue.type = revoke.type = refresh.type = "button";
  buttons.append(issue, revoke, refresh);
  const audit = node("ol", null, "manual-history");
  audit.setAttribute("aria-label", "수동 이용권 처리 이력");
  form.append(
    field("대상 환경", environment),
    field("지급 상품", kind),
    field("지급 기간 (일)", days),
    field("사유", reason),
    confirmLabel,
    buttons, status, audit,
  );
  details.append(form);

  let current = null;
  let loading = false;
  let sequence = 0;
  function buttonsLocked() {
    const active = current?.status === "ACTIVE";
    const blocked = loading || current === null;
    issue.disabled = blocked || active;
    revoke.disabled = blocked || !active;
    refresh.disabled = loading;
    environment.disabled = loading;
  }
  function setBusy(value, message) {
    loading = value;
    if (message) status.textContent = message;
    buttonsLocked();
  }
  function render(data) {
    current = data.grant;
    if (!current || current.ownerId !== member.ownerId ||
        current.mode !== environment.value || !Number.isSafeInteger(current.revision)) {
      throw new Error("Invalid manual grant result");
    }
    const states = {
      NONE: "수동 이용권 없음",
      ACTIVE: "수동 이용권 유효",
      EXPIRED: "수동 이용권 기간 만료",
      REVOKED: "운영자가 이용권 회수",
    };
    status.textContent = (states[current.status] ?? "상태 확인 불가") +
      (current.kind ? " · " + (tiers[current.kind] ?? "확인 필요") : "") +
      (current.expiresAtMs ? " · 종료 " + formatDate(current.expiresAtMs) : "") +
      " · Play 구매 기록은 변경되지 않습니다.";
    audit.replaceChildren();
    for (const entry of data.history ?? []) {
      const item = node("li", (entry.action === "ISSUE" ? "지급" : "회수") +
        " · " + formatDate(entry.recordedAtMs) +
        " · " + (tiers[entry.kind] ?? "확인 필요") +
        " · " + (entry.reason ?? ""), "manual-history-item");
      audit.append(item);
    }
    if (!audit.children.length) audit.append(node("li", "작업 기록 없음"));
    buttonsLocked();
  }
  async function load() {
    if (!api.currentUser()) return;
    const requestId = ++sequence;
    current = null;
    setBusy(true, "수동 이용권과 감사 기록 확인 중입니다.");
    audit.replaceChildren();
    try {
      const response = await api.read({ ownerId: member.ownerId, mode: environment.value });
      if (requestId !== sequence || !api.currentUser()) return;
      loading = false;
      render(response.data);
    } catch {
      if (requestId !== sequence) return;
      current = null;
      loading = false;
      status.textContent = "관리자 권한이나 수동 이용권 기록을 확인할 수 없습니다.";
      buttonsLocked();
    }
  }
  async function change(action) {
    if (loading || !current || !confirm.checked || !api.currentUser()) {
      status.textContent = "이용권 정보를 조회하고 확인 항목에 동의해 주세요.";
      return;
    }
    const text = reason.value.trim();
    const durationDays = Number(days.value);
    if (text.length < 10 || text.length > 280 ||
        action === "ISSUE" && (!Number.isInteger(durationDays) || durationDays < 1 || durationDays > 90)) {
      status.textContent = "사유 10~280자, 지급 기간 1~90일을 확인해 주세요.";
      return;
    }
    const kindValue = kind.value;
    if (action === "ISSUE" && !Object.hasOwn(tiers, kindValue)) return;
    const label = action === "ISSUE" ? "지급" : "회수";
    if (!window.confirm(
      "계정 " + member.ownerId.slice(0, 12) + "…\n" +
      "환경: " + environment.value + "\n" +
      "작업: 수동 이용권 " + label + "\n" +
      (action === "ISSUE" ? "상품: " + tiers[kindValue] + " · " + durationDays + "일\n" : "") +
      "Google Play 구매 기록은 변경되지 않습니다. 계속하시겠습니까?"
    )) return;
    const selectedMode = environment.value;
    const started = ++sequence;
    setBusy(true, "관리자 Google 재인증을 확인하는 중입니다.");
    try {
      await api.reauthenticate();
      if (!api.currentUser() || selectedMode !== environment.value || started !== sequence) return;
      status.textContent = "서버에 이용권 " + label + " 요청을 제출하는 중입니다.";
      const params = {
        ownerId: member.ownerId, mode: selectedMode,
        requestId: crypto.randomUUID().replaceAll("-", ""),
        expectedRevision: current.revision, reason: text,
        confirmation: action === "ISSUE" ? "ISSUE_MANUAL_ACCESS" : "REVOKE_MANUAL_ACCESS",
        ...(action === "ISSUE" ? { kind: kindValue, durationDays } : {}),
      };
      if (action === "ISSUE") await api.issue(params);
      else await api.revoke(params);
      if (started !== sequence || !api.currentUser()) return;
      confirm.checked = false;
      reason.value = "";
      status.textContent = "서버가 이용권 " + label + "을 접수했습니다. 실제 이용 혜택은 운영 플래그 확인이 필요합니다.";
      await load();
    } catch {
      if (started !== sequence) return;
      status.textContent = "서버에서 이용권을 " + label + "하지 못했습니다. 요청 상태를 다시 조회해 주세요.";
    } finally {
      if (started === sequence) {
        loading = false;
        buttonsLocked();
      }
    }
  }

  environment.addEventListener("change", () => {
    confirm.checked = false;
    void load();
  });
  details.addEventListener("toggle", () => {
    if (details.open) void load();
    else sequence++;
  });
  refresh.addEventListener("click", () => void load());
  issue.addEventListener("click", () => void change("ISSUE"));
  revoke.addEventListener("click", () => void change("REVOKE"));
  buttonsLocked();
  return details;
}

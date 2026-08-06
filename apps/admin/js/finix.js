/** FINIX shared client — personas, API helpers, toast UI. */
(function (global) {
  const PERSONAS = {
    farmer: {
      key: "farmer",
      label: "Farmer",
      email: "farmer@finix.lk",
      phone: "+94771110001",
      userId: "a1111111-1111-4111-8111-111111111101",
      accountId: "a2222222-2222-4222-8222-222222222201",
      accountNumber: "FINIX-SAV-00000001",
      route: "/farmer.html",
      icon: "agriculture",
      blurb: "Simple banking · crops & subsidies",
    },
    sme: {
      key: "sme",
      label: "SME",
      email: "sme@finix.lk",
      phone: "+94771110002",
      userId: "a1111111-1111-4111-8111-111111111102",
      accountId: "a2222222-2222-4222-8222-222222222202",
      accountNumber: "FINIX-CUR-00000002",
      route: "/sme.html",
      icon: "storefront",
      blurb: "Business & lending",
    },
    elder: {
      key: "elder",
      label: "Elder",
      email: "elder@finix.lk",
      phone: "+94771110003",
      userId: "a1111111-1111-4111-8111-111111111103",
      accountId: "a2222222-2222-4222-8222-222222222203",
      accountNumber: "FINIX-SAV-00000003",
      route: "/elder.html",
      icon: "accessibility_new",
      blurb: "Calm high-contrast view",
    },
  };

  const API = {
    account: () => global.FINIX_ACCOUNT_BASE || "/api/account",
    orchestrator: () => global.FINIX_ORCH_BASE || "/api/orchestrator",
    ledger: () => global.FINIX_LEDGER_BASE || "/api/ledger",
    ussd: () => global.FINIX_USSD_BASE || "/api/ussd",
  };

  function formatMoney(value) {
    if (value == null) return "—";
    if (typeof value === "string") {
      const m = value.match(/^([A-Z]{3})\s+([-\d.]+)$/);
      if (m) {
        const n = Number(m[2]);
        return m[1] + " " + n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
      }
      return value;
    }
    if (typeof value === "object" && value.amount != null) {
      return formatMoney((value.currency || "LKR") + " " + value.amount);
    }
    return String(value);
  }

  function moneyAmount(value) {
    if (typeof value === "string") {
      const m = value.match(/([-\d.]+)\s*$/);
      return m ? Number(m[1]) : NaN;
    }
    if (value && value.amount != null) return Number(value.amount);
    return Number(value);
  }

  function friendlyError(raw) {
    if (raw == null) return "Request failed";
    if (raw instanceof Error) {
      if (raw.data && typeof raw.data === "object") {
        return raw.data.detail || raw.data.title || raw.data.message || friendlyError(raw.message);
      }
      return friendlyError(raw.message);
    }
    if (typeof raw === "object") {
      return raw.detail || raw.title || raw.message || JSON.stringify(raw);
    }
    const text = String(raw).replace(/^Error:\s*/, "");
    try {
      const j = JSON.parse(text);
      if (j && typeof j === "object") {
        return j.detail || j.title || j.message || text;
      }
    } catch (_) {}
    return text || "Request failed";
  }

  async function api(base, path, opts = {}) {
    const headers = Object.assign({}, opts.headers || {});
    if (opts.body && !headers["content-type"]) headers["content-type"] = "application/json";
    if (opts.idempotency) headers["Idempotency-Key"] = opts.idempotency;
    const res = await fetch(base + path, {
      method: opts.method || "GET",
      headers,
      body: opts.body ? (typeof opts.body === "string" ? opts.body : JSON.stringify(opts.body)) : undefined,
    });
    const text = await res.text();
    let data = text;
    try { data = text ? JSON.parse(text) : null; } catch (_) {}
    if (!res.ok) {
      const err = new Error(friendlyError(data != null ? data : (text || res.status)));
      err.status = res.status;
      err.data = data;
      throw err;
    }
    return data;
  }

  async function getAccount(accountId) {
    return api(API.account(), "/api/v1/accounts/" + accountId);
  }

  async function listAccounts(ownerUserId) {
    return api(API.account(), "/api/v1/accounts?ownerUserId=" + encodeURIComponent(ownerUserId));
  }

  async function transfer({ fromAccountId, toAccountId, amount, newDevice = false, velocity1h = 0 }) {
    return api(API.orchestrator(), "/api/v1/transfers", {
      method: "POST",
      idempotency: "web-" + crypto.randomUUID(),
      body: {
        fromAccountId,
        toAccountId,
        amount: amount.startsWith("LKR") ? amount : "LKR " + Number(amount).toFixed(2),
        newDevice,
        velocity1h,
      },
    });
  }

  async function stepUp(transferId, otpCode) {
    return api(API.orchestrator(), "/api/v1/transfers/" + transferId + "/step-up", {
      method: "POST",
      body: { otpCode },
    });
  }

  async function ledgerVerify() {
    return api(API.ledger(), "/api/v1/ledger/verify");
  }

  async function ledgerProof(txId) {
    return api(API.ledger(), "/api/v1/ledger/proof/" + txId);
  }

  function savePersona(key) {
    const p = PERSONAS[key];
    if (!p) return;
    sessionStorage.setItem("finixPersona", JSON.stringify(p));
  }

  function currentPersona() {
    try {
      const raw = sessionStorage.getItem("finixPersona");
      if (raw) return JSON.parse(raw);
    } catch (_) {}
    return PERSONAS.farmer;
  }

  function toast(message, kind = "info") {
    let host = document.getElementById("finix-toast-host");
    if (!host) {
      host = document.createElement("div");
      host.id = "finix-toast-host";
      host.className = "toast-host";
      document.body.appendChild(host);
    }
    const el = document.createElement("div");
    el.className = "toast toast-" + kind;
    el.textContent = message;
    host.appendChild(el);
    requestAnimationFrame(() => el.classList.add("show"));
    setTimeout(() => {
      el.classList.remove("show");
      setTimeout(() => el.remove(), 280);
    }, 4200);
  }

  function paintMeshStatus(el) {
    if (!el) return;
    const online = navigator.onLine;
    el.className = "mesh-pill" + (online ? "" : " offline");
    el.innerHTML = online
      ? '<span class="dot"></span> Mesh Active'
      : '<span class="dot"></span> Offline';
  }

  function bindMeshStatus(selector) {
    const el = typeof selector === "string" ? document.querySelector(selector) : selector;
    const paint = () => paintMeshStatus(el);
    window.addEventListener("online", paint);
    window.addEventListener("offline", paint);
    paint();
  }

  function setText(id, value) {
    const el = document.getElementById(id);
    if (el) el.textContent = value;
  }

  function skeleton(el, on) {
    if (!el) return;
    el.classList.toggle("is-loading", !!on);
  }

  global.Finix = {
    PERSONAS,
    API,
    formatMoney,
    moneyAmount,
    api,
    friendlyError,
    getAccount,
    listAccounts,
    transfer,
    stepUp,
    ledgerVerify,
    ledgerProof,
    savePersona,
    currentPersona,
    toast,
    bindMeshStatus,
    setText,
    skeleton,
  };
})(window);

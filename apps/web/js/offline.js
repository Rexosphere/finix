/** FINIX offline voucher helpers — WebCrypto ECDSA P-256, compact CBOR, Base45. */
(function (global) {
  const BASE45 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";
  const DEMO = {
    farmer: {
      userId: "a1111111-1111-4111-8111-111111111101",
      accountId: "a2222222-2222-4222-8222-222222222201",
      accountNumber: "FINIX-SAV-00000001",
      phone: "+94771110001",
    },
    sme: {
      userId: "a1111111-1111-4111-8111-111111111102",
      accountId: "a2222222-2222-4222-8222-222222222202",
      accountNumber: "FINIX-CUR-00000002",
      phone: "+94771110002",
    },
    elder: {
      userId: "a1111111-1111-4111-8111-111111111103",
      accountId: "a2222222-2222-4222-8222-222222222203",
      accountNumber: "FINIX-SAV-00000003",
      phone: "+94771110003",
    },
  };

  const DB_NAME = "finix-offline";
  const DB_VERSION = 1;

  function openDb() {
    return new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains("meta")) db.createObjectStore("meta");
        if (!db.objectStoreNames.contains("outbox")) {
          const store = db.createObjectStore("outbox", { keyPath: "id" });
          store.createIndex("status", "status");
        }
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  async function idbGet(store, key) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(store, "readonly");
      const r = tx.objectStore(store).get(key);
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error);
    });
  }

  async function idbPut(store, value, key) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(store, "readwrite");
      const os = tx.objectStore(store);
      const r = key === undefined ? os.put(value) : os.put(value, key);
      r.onsuccess = () => resolve();
      r.onerror = () => reject(r.error);
    });
  }

  async function idbAll(store) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(store, "readonly");
      const r = tx.objectStore(store).getAll();
      r.onsuccess = () => resolve(r.result || []);
      r.onerror = () => reject(r.error);
    });
  }

  function b64(bytes) {
    let s = "";
    bytes = new Uint8Array(bytes);
    for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return btoa(s);
  }

  function fromB64(s) {
    const bin = atob(s);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  function encodeUtf8(str) {
    return new TextEncoder().encode(str);
  }

  function cborEncodeMap(obj) {
    const keys = Object.keys(obj);
    const chunks = [];
    chunks.push(encodeCborHeader(0xa0, keys.length));
    for (const k of keys) {
      chunks.push(cborEncodeString(k));
      const v = obj[k];
      if (typeof v === "string") chunks.push(cborEncodeString(v));
      else if (typeof v === "number") chunks.push(cborEncodeInt(v));
      else if (v instanceof Uint8Array) chunks.push(cborEncodeBytes(v));
      else throw new Error("unsupported cbor value");
    }
    return concat(chunks);
  }

  function encodeCborHeader(major, n) {
    if (n < 24) return Uint8Array.of(major | n);
    if (n < 256) return Uint8Array.of(major | 24, n);
    if (n < 65536) return Uint8Array.of(major | 25, (n >> 8) & 0xff, n & 0xff);
    return Uint8Array.of(
      major | 26,
      (n >>> 24) & 0xff,
      (n >>> 16) & 0xff,
      (n >>> 8) & 0xff,
      n & 0xff,
    );
  }

  function cborEncodeString(s) {
    const bytes = encodeUtf8(s);
    return concat([encodeCborHeader(0x60, bytes.length), bytes]);
  }

  function cborEncodeBytes(bytes) {
    return concat([encodeCborHeader(0x40, bytes.length), bytes]);
  }

  function cborEncodeInt(n) {
    if (n >= 0) return encodeCborHeader(0x00, n);
    return encodeCborHeader(0x20, -1 - n);
  }

  function concat(parts) {
    const len = parts.reduce((a, p) => a + p.length, 0);
    const out = new Uint8Array(len);
    let o = 0;
    for (const p of parts) {
      out.set(p, o);
      o += p.length;
    }
    return out;
  }

  function base45Encode(bytes) {
    let out = "";
    for (let i = 0; i < bytes.length; i += 2) {
      if (i + 1 < bytes.length) {
        const n = bytes[i] * 256 + bytes[i + 1];
        const c = n % 45;
        const d = Math.floor(n / 45) % 45;
        const e = Math.floor(n / (45 * 45));
        out += BASE45[c] + BASE45[d] + BASE45[e];
      } else {
        const n = bytes[i];
        const c = n % 45;
        const d = Math.floor(n / 45);
        out += BASE45[c] + BASE45[d];
      }
    }
    return out;
  }

  function signingPayload(v) {
    return encodeUtf8(
      [
        v.payerAccountId,
        v.payeeAccountId,
        String(v.amountMinor),
        v.currency,
        v.deviceId,
        String(v.deviceSeq),
        v.nonce,
        String(v.validUntilEpochMs),
      ].join("|"),
    );
  }

  async function ensureDeviceKey(deviceId) {
    const existing = await idbGet("meta", "deviceKey:" + deviceId);
    if (existing) {
      const privateKey = await crypto.subtle.importKey(
        "jwk",
        existing.privateJwk,
        { name: "ECDSA", namedCurve: "P-256" },
        false,
        ["sign"],
      );
      return { privateKey, publicKeySpkiBase64: existing.publicKeySpkiBase64, deviceId };
    }
    const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
    const privateJwk = await crypto.subtle.exportKey("jwk", pair.privateKey);
    delete privateJwk.key_ops;
    const spki = await crypto.subtle.exportKey("spki", pair.publicKey);
    const publicKeySpkiBase64 = b64(spki);
    const nonExtractable = await crypto.subtle.importKey(
      "jwk",
      privateJwk,
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["sign"],
    );
    await idbPut(
      "meta",
      { privateJwk, publicKeySpkiBase64, deviceId },
      "deviceKey:" + deviceId,
    );
    return { privateKey: nonExtractable, publicKeySpkiBase64, deviceId };
  }

  async function nextSeq(deviceId) {
    const key = "seq:" + deviceId;
    const cur = (await idbGet("meta", key)) || 0;
    const next = cur + 1;
    await idbPut("meta", next, key);
    return next;
  }

  async function createVoucher({ payer, payee, amountLkr, hoursValid }) {
    const deviceId = "web-" + payer.accountId.slice(0, 8);
    const key = await ensureDeviceKey(deviceId);
    const amountMinor = Math.round(Number(amountLkr) * 100);
    const deviceSeq = await nextSeq(deviceId);
    const nonce = crypto.randomUUID().replace(/-/g, "").slice(0, 16);
    const validUntilEpochMs = Date.now() + hoursValid * 3600 * 1000;
    const voucher = {
      payerAccountId: payer.accountId,
      payeeAccountId: payee.accountId,
      amountMinor,
      currency: "LKR",
      deviceId,
      deviceSeq,
      nonce,
      validUntilEpochMs,
    };
    const sig = await crypto.subtle.sign(
      { name: "ECDSA", hash: "SHA-256" },
      key.privateKey,
      signingPayload(voucher),
    );
    voucher.signatureBase64 = b64(sig);
    voucher.publicKeySpkiBase64 = key.publicKeySpkiBase64;
    voucher.ownerUserId = payer.userId;
    voucher.status = "queued";
    voucher.id = crypto.randomUUID();
    voucher.createdAt = Date.now();
    const cbor = cborEncodeMap({
      p: voucher.payerAccountId,
      e: voucher.payeeAccountId,
      a: voucher.amountMinor,
      c: voucher.currency,
      d: voucher.deviceId,
      s: voucher.deviceSeq,
      n: voucher.nonce,
      u: voucher.validUntilEpochMs,
      g: fromB64(voucher.signatureBase64),
    });
    voucher.base45 = base45Encode(cbor);
    await idbPut("outbox", voucher);
    return voucher;
  }

  function accountBase() {
    return global.FINIX_ACCOUNT_BASE || "/api/account";
  }

  async function registerDevice(voucher) {
    const res = await fetch(accountBase() + "/api/v1/offline/devices", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        deviceId: voucher.deviceId,
        ownerUserId: voucher.ownerUserId,
        accountId: voucher.payerAccountId,
        publicKeySpkiBase64: voucher.publicKeySpkiBase64,
      }),
    });
    if (!res.ok) throw new Error("register failed: " + res.status);
  }

  async function reconcileVoucher(voucher) {
    await registerDevice(voucher);
    const res = await fetch(accountBase() + "/api/v1/offline/vouchers/reconcile", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        deviceId: voucher.deviceId,
        payerAccountId: voucher.payerAccountId,
        payeeAccountId: voucher.payeeAccountId,
        amount: "LKR " + (voucher.amountMinor / 100).toFixed(2),
        deviceSeq: voucher.deviceSeq,
        nonce: voucher.nonce,
        validUntilEpochMs: voucher.validUntilEpochMs,
        signatureBase64: voucher.signatureBase64,
      }),
    });
    const text = await res.text();
    if (!res.ok) {
      voucher.status = "rejected";
      voucher.error = text;
      await idbPut("outbox", voucher);
      throw new Error(text || "reconcile failed");
    }
    voucher.status = "settled";
    voucher.server = text;
    await idbPut("outbox", voucher);
    return JSON.parse(text);
  }

  async function reconcileAll() {
    const items = await idbAll("outbox");
    const queued = items.filter((v) => v.status === "queued" || v.status === "syncing");
    const results = [];
    for (const v of queued) {
      v.status = "syncing";
      await idbPut("outbox", v);
      try {
        results.push(await reconcileVoucher(v));
      } catch (err) {
        results.push({ error: String(err), voucher: v });
      }
    }
    return results;
  }

  global.FinixOffline = {
    DEMO,
    createVoucher,
    reconcileVoucher,
    reconcileAll,
    listOutbox: () => idbAll("outbox"),
    ensureDeviceKey,
  };
})(window);

/**
 * Smoke test against a live notification-service (or starts one briefly).
 * Usage: npm start &  npm run smoke
 * Or:    node scripts/smoke.js  (self-starts if PORT free / uses BASE_URL)
 */
import { spawn } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

const BASE = process.env.BASE_URL || "http://127.0.0.1:8093";
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

async function request(method, urlPath, body) {
  const res = await fetch(`${BASE}${urlPath}`, {
    method,
    headers: body ? { "Content-Type": "application/json" } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    json = text;
  }
  return { status: res.status, json };
}

async function healthy() {
  try {
    const { status } = await request("GET", "/health");
    return status === 200;
  } catch {
    return false;
  }
}

async function main() {
  let child;
  if (!(await healthy())) {
    const logs = [];
    child = spawn(process.execPath, ["src/index.js"], {
      cwd: root,
      env: { ...process.env, PORT: "8093" },
      stdio: ["ignore", "pipe", "pipe"],
    });
    child.stdout.on("data", (d) => logs.push(String(d)));
    child.stderr.on("data", (d) => logs.push(String(d)));
    child.on("exit", (code) => {
      if (code && code !== 0) logs.push(`exit code ${code}`);
    });
    for (let i = 0; i < 40; i++) {
      await sleep(100);
      if (await healthy()) break;
      if (child.exitCode !== null) break;
    }
    if (!(await healthy())) {
      console.error("service failed to start");
      if (logs.length) console.error(logs.join("").trim());
      child?.kill();
      process.exit(1);
    }
  }

  try {
    const health = await request("GET", "/health");
    if (health.status !== 200 || health.json.status !== "ok") {
      throw new Error(`health failed: ${JSON.stringify(health)}`);
    }

    const notify = await request("POST", "/v1/notify", {
      channel: "sms",
      locale: "si",
      template: "fraud_alert",
      to: "+94771234567",
      vars: { name: "Kamal", account: "LK-9", detail: "ATM abroad" },
    });
    if (notify.status !== 201) {
      throw new Error(`notify failed: ${JSON.stringify(notify)}`);
    }
    if (!String(notify.json.body).includes("Kamal")) {
      throw new Error("rendered body missing var");
    }

    const list = await request("GET", "/v1/messages");
    if (list.status !== 200 || !Array.isArray(list.json.messages)) {
      throw new Error(`messages failed: ${JSON.stringify(list)}`);
    }
    if (list.json.messages.length < 1) {
      throw new Error("expected at least one stored message");
    }

    console.log("smoke ok:", {
      messageId: notify.json.id,
      locale: notify.json.locale,
      count: list.json.messages.length,
    });
  } finally {
    child?.kill();
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});

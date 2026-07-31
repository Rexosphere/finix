/**
 * FINIX Notification Service — multi-locale channel fan-out (demo).
 *
 * Port 8093. In-memory message store. Templates: transfer_receipt,
 * step_up_challenge, loan_approved, fraud_alert (en | si | ta).
 *
 *   npm install && npm start
 *   npm test && npm run smoke
 */
import express from "express";
import { renderTemplate, listTemplates } from "./templates.js";
import { MessageStore } from "./store.js";

const PORT = Number(process.env.PORT || 8093);
const CHANNELS = new Set(["sms", "email", "push", "voice"]);
const LOCALES = new Set(["en", "si", "ta"]);

const app = express();
const store = new MessageStore();

app.use(express.json({ limit: "64kb" }));

app.get("/health", (_req, res) => {
  res.json({ status: "ok", service: "notification-service" });
});

app.get("/v1/templates", (_req, res) => {
  res.json({ templates: listTemplates() });
});

app.post("/v1/notify", (req, res) => {
  const { channel, locale = "en", template, to, vars = {} } = req.body ?? {};

  if (!CHANNELS.has(channel)) {
    return res.status(400).json({
      error: `channel must be one of: ${[...CHANNELS].join(", ")}`,
    });
  }
  if (!LOCALES.has(locale)) {
    return res.status(400).json({
      error: `locale must be one of: ${[...LOCALES].join(", ")}`,
    });
  }
  if (!template || typeof template !== "string") {
    return res.status(400).json({ error: "template is required" });
  }
  if (!to || typeof to !== "string") {
    return res.status(400).json({ error: "to is required" });
  }

  let rendered;
  try {
    rendered = renderTemplate(template, locale, vars);
  } catch (err) {
    return res.status(400).json({ error: err.message });
  }

  const message = store.add({
    channel,
    locale,
    template,
    to,
    vars,
    subject: rendered.subject,
    body: rendered.body,
  });

  res.status(201).json(message);
});

app.get("/v1/messages", (_req, res) => {
  res.json({ messages: store.list() });
});

if (process.env.NODE_ENV !== "test") {
  app.listen(PORT, () => {
    console.log(`finix notification-service listening on :${PORT}`);
  });
}

export { app, store };

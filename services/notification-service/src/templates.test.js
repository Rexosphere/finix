import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { listTemplates, renderTemplate } from "./templates.js";

describe("notification templates", () => {
  it("exposes the four M8 templates", () => {
    assert.deepEqual(listTemplates().sort(), [
      "fraud_alert",
      "loan_approved",
      "step_up_challenge",
      "transfer_receipt",
    ]);
  });

  it("renders transfer_receipt in en/si/ta with vars", () => {
    const vars = {
      name: "Amal",
      amount: "1,500.00",
      currency: "LKR",
      creditor: "Nimal",
      ref: "TX-1",
    };
    const en = renderTemplate("transfer_receipt", "en", vars);
    assert.match(en.body, /Amal/);
    assert.match(en.body, /1,500\.00/);
    assert.match(en.body, /TX-1/);

    const si = renderTemplate("transfer_receipt", "si", vars);
    assert.match(si.subject, /රිසිට්පත/);
    assert.match(si.body, /Amal/);

    const ta = renderTemplate("transfer_receipt", "ta", vars);
    assert.match(ta.subject, /ரசீது/);
    assert.match(ta.body, /Nimal/);
  });

  it("rejects unknown templates", () => {
    assert.throws(
      () => renderTemplate("nope", "en", {}),
      /unknown template/,
    );
  });
});

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

/**
 * FINIX demo-scale load — internal transfer via orchestrator.
 *
 * Prefer transfer when the stack is up (permit-all demo profile).
 * If transfer returns auth/5xx consistently, set FINIX_K6_MODE=health.
 *
 *   k6 run tests/load/transfer.js
 *   FINIX_K6_MODE=health k6 run tests/load/transfer.js
 */

const BASE = __ENV.ORCH_URL || 'http://localhost:8085';
const MODE = (__ENV.FINIX_K6_MODE || 'transfer').toLowerCase();
const FROM = __ENV.FROM_ACCOUNT || 'a2222222-2222-4222-8222-222222222201';
const TO = __ENV.TO_ACCOUNT || 'a2222222-2222-4222-8222-222222222202';

const errorRate = new Rate('transfer_errors');

export const options = {
  scenarios: {
    demo_steady: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 5),
      duration: __ENV.DURATION || '30s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500'], // demo laptop threshold — not 10k TPS claim
    transfer_errors: ['rate<0.2'],
  },
};

function hitHealth() {
  const res = http.get(`${BASE}/actuator/health`);
  const ok = check(res, {
    'health 200': (r) => r.status === 200,
  });
  errorRate.add(!ok);
}

function hitTransfer() {
  const payload = JSON.stringify({
    fromAccountId: FROM,
    toAccountId: TO,
    amount: 'LKR 1.00',
  });
  const res = http.post(`${BASE}/api/v1/transfers`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': `k6-${__VU}-${__ITER}-${Date.now()}`,
    },
  });
  const ok = check(res, {
    'transfer accepted': (r) => r.status === 200 || r.status === 201,
    'not 5xx': (r) => r.status < 500,
  });
  errorRate.add(!ok);
}

export default function () {
  if (MODE === 'health') {
    hitHealth();
  } else {
    hitTransfer();
  }
  sleep(0.3);
}

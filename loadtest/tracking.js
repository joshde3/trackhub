// k6 load test for the Phase 1 numbers. Run against LoadTestApplication (see README.md here):
//   docker run --rm --network host -v "$PWD/loadtest:/scripts" grafana/k6:1.4.2 run /scripts/tracking.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
// Same values as LoadTestConfiguration and src/test/resources/config/application.yml. Not secrets.
const SIGNING_KEY = 'load-test-only-load-test-only-load-test-only';
const AUDIENCE = 'test-client-id.apps.googleusercontent.com';
const PARCELS_PER_USER = 5;

export const options = {
  scenarios: {
    users: { executor: 'constant-vus', vus: Number(__ENV.VUS || 20), duration: __ENV.DURATION || '60s' },
  },
  // Thresholds make k6 print each endpoint's latency on its own line.
  thresholds: {
    'http_req_duration{name:add}': ['p(95)<2000'],
    'http_req_duration{name:list}': ['p(95)<500'],
    'http_req_duration{name:get}': ['p(95)<500'],
    'http_req_duration{name:refresh}': ['p(95)<500'],
    'checks': ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

function b64url(text) {
  return encoding.b64encode(text, 'rawurl');
}

function token(sub) {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = b64url(JSON.stringify({
    iss: 'https://accounts.google.com', aud: AUDIENCE, sub, email: `${sub}@example.com`,
    email_verified: true, iat: now, exp: now + 3600,
  }));
  const signature = crypto.hmac('sha256', SIGNING_KEY, `${header}.${payload}`, 'base64rawurl');
  return `${header}.${payload}.${signature}`;
}

// Per-VU state: each virtual user is one person with a few parcels.
const run = Math.floor(Date.now() / 1000) % 100000;
let auth = null;
let ids = [];

function setUpUser() {
  auth = { headers: { Authorization: `Bearer ${token(`k6-${run}-${__VU}`)}`, 'Content-Type': 'application/json' } };
  for (let i = 0; i < PARCELS_PER_USER; i++) {
    const number = `LT${run}V${__VU}N${i}IN`;
    const res = http.post(`${BASE}/api/shipments`, JSON.stringify({ trackingNumber: number }), { ...auth, tags: { name: 'add' } });
    check(res, { 'added': (r) => r.status === 201 });
    if (res.status === 201) {
      ids.push(res.json('id'));
    }
  }
}

export default function () {
  if (auth === null) {
    setUpUser();
  }
  const id = ids[Math.floor(Math.random() * ids.length)];

  check(http.get(`${BASE}/api/shipments?size=20`, { ...auth, tags: { name: 'list' } }), { 'listed': (r) => r.status === 200 });
  check(http.get(`${BASE}/api/shipments/${id}`, { ...auth, tags: { name: 'get' } }), { 'got': (r) => r.status === 200 });
  check(http.post(`${BASE}/api/shipments/${id}/refresh`, null, { ...auth, tags: { name: 'refresh' } }), { 'refreshed': (r) => r.status === 200 });
  sleep(0.2);
}

export function teardown() {
  const admin = { headers: { Authorization: `Bearer ${token('k6-metrics')}` } };
  const count = (result) => {
    const res = http.get(`${BASE}/actuator/metrics/cache.gets?tag=cache:tracking&tag=result:${result}`, admin);
    return res.status === 200 ? res.json('measurements.0.value') : NaN;
  };
  const hits = count('hit');
  const misses = count('miss');
  const calls = http.get(`${BASE}/actuator/metrics/trackhub.carrier.calls`, admin);
  console.log(`cache hits=${hits} misses=${misses} hit rate=${(100 * hits / (hits + misses)).toFixed(1)}%`);
  if (calls.status === 200) {
    console.log(`carrier calls=${calls.json('measurements.0.value')}`);
  }
}

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const rateLimited429 = new Counter('rate_limited_429');
const http5xx = new Counter('http_5xx');

function pickUserId() {
  const raw = __ENV.STRESS_USER_IDS || '550e8400-e29b-41d4-a716-446655440000';
  const ids = raw.split(',').map((s) => s.trim()).filter(Boolean);
  return ids[(__VU - 1) % ids.length];
}

function track(res) {
  if (res.status === 429) rateLimited429.add(1);
  if (res.status >= 500) http5xx.add(1);
}

export const options = {
  stages: [
    { duration: '1m', target: 1000 },  // ramp-up
    { duration: '3m', target: 1000 },  // steady state
    { duration: '1m', target: 0 },     // ramp-down
  ],
  thresholds: {
    http_req_duration: ['p(95)<300', 'p(99)<800'],
    http_req_failed: ['rate<0.01'],
    http_5xx: ['count==0'],
    rate_limited_429: ['count==0'],
  },
};

export default function () {
  const userId = pickUserId();
  const headers = { 'x-user-id': userId, 'Content-Type': 'application/json' };

  // 1. Login
  let res = http.get('http://localhost:3000/v1/groups', { headers });
  track(res);
  check(res, { 'groups 200': (r) => r.status === 200 });

  // 2. Heartbeat
  res = http.post(
    'http://localhost:3000/v1/telemetry/heartbeat',
    JSON.stringify({
      latitude: 10.35,
      longitude: -67.04,
      heading: 0,
      speedKmh: 0,
    }),
    { headers }
  );
  track(res);
  check(res, { 'heartbeat 200': (r) => r.status === 200 });

  // 3. SOS cercanos
  res = http.get(
    'http://localhost:3000/v1/sos/nearby?lat=10.35&lng=-67.04&radiusKm=5',
    { headers }
  );
  track(res);
  check(res, { 'sos 200': (r) => r.status === 200 });

  sleep(1); // think time
}
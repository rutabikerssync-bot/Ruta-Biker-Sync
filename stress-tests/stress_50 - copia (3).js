import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '1m', target: 50 },    // ramp-up
    { duration: '3m', target: 50 },    // steady state
    { duration: '1m', target: 0 },     // ramp-down
  ],
  thresholds: {
    http_req_duration: ['p(95)<300', 'p(99)<800'],
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const userId = '550e8400-e29b-41d4-a716-446655440000'; // UUID válido
  const headers = { 'x-user-id': userId, 'Content-Type': 'application/json' };

  // 1. Login
  let res = http.get('http://localhost:3000/v1/groups', { headers });
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
  check(res, { 'heartbeat 200': (r) => r.status === 200 });

  // 3. SOS cercanos
  res = http.get(
    'http://localhost:3000/v1/sos/nearby?lat=10.35&lng=-67.04&radiusKm=5',
    { headers }
  );
  check(res, { 'sos 200': (r) => r.status === 200 });

  sleep(1); // think time
}
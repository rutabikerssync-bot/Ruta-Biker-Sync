import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

// Test dedicado del rate limiter global (10.000 req/min por clave).
// Objetivo: verificar que al superar el limite el backend responde 429 (no 5xx),
// en lugar de usar el test de capacidad para descubrirlo.
const rateLimited429 = new Counter('rate_limited_429');

export const options = {
  scenarios: {
    burst_single_user: {
      executor: 'constant-vus',
      vus: 4,
      duration: '90s',
    },
  },
  thresholds: {
    rate_limited_429: ['count>0'],
    checks: ['rate>0.99'],
  },
};

export default function () {
  const userId =
    __ENV.STRESS_SINGLE_USER_ID || '550e8400-e29b-41d4-a716-446655440000';

  const res = http.get('http://localhost:3000/v1/groups', {
    headers: { 'x-user-id': userId },
  });

  if (res.status === 429) rateLimited429.add(1);

  check(res, {
    'status 200 or 429': (r) => r.status === 200 || r.status === 429,
  });

  if (res.status === 429) {
    check(res, {
      '429 has Retry-After': (r) =>
        !!(r.headers['Retry-After'] || r.headers['retry-after']),
    });
  }

  sleep(0.01);
}

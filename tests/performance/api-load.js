import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:18080';
const profile = __ENV.PROFILE || 'smoke';
const allowedTarget = /^https?:\/\/(localhost|127\.0\.0\.1|\[::1\]|host\.docker\.internal|server)(:\d+)?(\/|$)/;

if (!allowedTarget.test(baseUrl)) {
  throw new Error(`Safety guard rejected non-local target: ${baseUrl}`);
}

const profiles = {
  smoke: { vus: 2, duration: '10s' },
  load: { stages: [{ duration: '15s', target: 10 }, { duration: '30s', target: 30 }, { duration: '15s', target: 0 }] },
  stress: { stages: [{ duration: '20s', target: 30 }, { duration: '30s', target: 80 }, { duration: '20s', target: 120 }, { duration: '20s', target: 0 }] },
};

if (!profiles[profile]) {
  throw new Error(`Unknown PROFILE=${profile}; expected smoke, load, or stress`);
}

export const options = {
  ...profiles[profile],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    checks: ['rate>0.99'],
  },
};

const jwtToken = (__ENV.JWT_TOKEN || '').trim();
if (!jwtToken) {
  throw new Error('JWT_TOKEN is required; the load test never uses demo Basic credentials');
}
const auth = `Bearer ${jwtToken}`;

export default function () {
  const tracePrefix = `k6-${__VU}-${__ITER}`;
  const healthRequestId = `${tracePrefix}-health`;
  const policyRequestId = `${tracePrefix}-policies`;
  const matchRequestId = `${tracePrefix}-matches`;
  const health = http.get(`${baseUrl}/api/v1/health`, {
    headers: { 'X-Request-Id': healthRequestId },
  });
  check(health, {
    'health is 200': (response) => response.status === 200,
    'health request ID is echoed': (response) => response.headers['X-Request-Id'] === healthRequestId,
  });

  const policies = http.get(`${baseUrl}/api/v1/policies`, {
    headers: {
      Authorization: auth,
      'Content-Type': 'application/json',
      'X-Request-Id': policyRequestId,
    },
  });
  check(policies, {
    'policies is 200': (response) => response.status === 200,
    'policies has data': (response) => Boolean(response.json('data')),
    'policy request ID is echoed': (response) => response.headers['X-Request-Id'] === policyRequestId,
  });

  const matchPayload = JSON.stringify({
    demandCompany: '压测需求企业',
    demandTitle: '燃气管线阀门供应',
    scene: '燃气管网',
    requirements: '阀门 泄漏监测 北京交付',
    limit: 5,
  });
  const matches = http.post(`${baseUrl}/api/v1/matches`, matchPayload, {
    headers: {
      Authorization: auth,
      'Content-Type': 'application/json',
      'X-Request-Id': matchRequestId,
    },
  });
  check(matches, {
    'matching is 200': (response) => response.status === 200,
    'matching request ID is echoed': (response) => response.headers['X-Request-Id'] === matchRequestId,
  });
  sleep(0.2);
}

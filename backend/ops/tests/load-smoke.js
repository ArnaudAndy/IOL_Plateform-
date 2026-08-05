import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = (__ENV.IOL_BASE_URL || 'http://localhost').replace(/\/$/, '');
const token = (__ENV.IOL_ACCESS_TOKEN || '').trim();

export const options = {
  vus: Number(__ENV.IOL_LOAD_VUS || 10),
  duration: __ENV.IOL_LOAD_DURATION || '30s',
  insecureSkipTLSVerify: String(__ENV.IOL_INSECURE_TLS || 'false') === 'true',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500', 'p(99)<1200'],
    checks: ['rate>0.99'],
  },
};

export default function () {
  const readiness = http.get(`${baseUrl}/health/ready`, {
    tags: { endpoint: 'readiness' },
  });
  check(readiness, {
    'readiness retourne 200': (response) => response.status === 200,
    'readiness est UP': (response) => response.body && response.body.includes('UP'),
  });

  const liveness = http.get(`${baseUrl}/health/live`, {
    tags: { endpoint: 'liveness' },
  });
  check(liveness, {
    'liveness retourne 200': (response) => response.status === 200,
  });

  if (token) {
    const workflows = http.get(`${baseUrl}/api/workflows`, {
      headers: { Authorization: `Bearer ${token}` },
      tags: { endpoint: 'workflows' },
    });
    check(workflows, {
      'liste workflows autorisee': (response) => response.status === 200,
    });
  }

  sleep(0.2);
}

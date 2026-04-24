import http from 'k6/http';
import { check, group, sleep } from 'k6';

const BASE_URL = (__ENV.BASE_URL || '').replace(/\/$/, '');
const SCENARIO = __ENV.SCENARIO || 'smoke';
const ACTIVE_PERSONAS = parsePersonaFilter(__ENV.PERSONAS || 'home,preRide,inRide,write,health');
const TEST_ID = __ENV.TEST_ID || `bike-${SCENARIO}`;
const SUMMARY_DIR = (__ENV.SUMMARY_DIR || 'ops/loadtest/results').replace(/\/$/, '');

if (!BASE_URL) {
  throw new Error('BASE_URL 환경변수는 필수입니다. 예: BASE_URL=http://localhost:8080');
}

function intEnv(name, fallback) {
  const raw = __ENV[name];
  if (!raw) return fallback;

  const parsed = Number.parseInt(raw, 10);
  return Number.isNaN(parsed) ? fallback : parsed;
}

function stringEnv(name, fallback) {
  return __ENV[name] || fallback;
}

function parsePersonaFilter(raw) {
  return raw
    .split(',')
    .map((value) => value.trim())
    .filter(Boolean);
}

function isPersonaEnabled(name) {
  return ACTIVE_PERSONAS.includes(name);
}

function baseRequestTags(extra = {}) {
  return {
    testid: TEST_ID,
    scenario_profile: SCENARIO,
    ...extra,
  };
}

function weightedVus(total, weightPercent) {
  if (weightPercent <= 0 || total <= 0) {
    return 0;
  }

  return Math.max(1, Math.round((total * weightPercent) / 100));
}

function addSmokeScenario(scenarios, personaName, execName, iterations) {
  if (!isPersonaEnabled(personaName)) {
    return;
  }

  scenarios[`smoke_${personaName}`] = {
    executor: 'shared-iterations',
    exec: execName,
    vus: intEnv(`SMOKE_${personaName.toUpperCase()}_VUS`, 1),
    iterations: intEnv(`SMOKE_${personaName.toUpperCase()}_ITERATIONS`, iterations),
    maxDuration: stringEnv('SMOKE_MAX_DURATION', '1m'),
  };
}

function addRampingScenario(scenarios, personaName, execName, options) {
  if (!isPersonaEnabled(personaName) || options.target <= 0) {
    return;
  }

  scenarios[`${SCENARIO}_${personaName}`] = {
    executor: 'ramping-vus',
    exec: execName,
    startVUs: 0,
    stages: [
      { duration: options.rampUp, target: options.target },
      { duration: options.hold, target: options.target },
      { duration: options.rampDown, target: 0 },
    ],
    gracefulRampDown: '30s',
  };
}

function buildOptions() {
  const p95Ms = intEnv('P95_MS', 800);
  const errorRateMax = Number.parseFloat(__ENV.ERROR_RATE_MAX || '0.01');
  const homeWeight = intEnv('HOME_WEIGHT_PERCENT', 35);
  const preRideWeight = intEnv('PRERIDE_WEIGHT_PERCENT', 30);
  const inRideWeight = intEnv('INRIDE_WEIGHT_PERCENT', 20);
  const writeWeight = intEnv('WRITE_WEIGHT_PERCENT', 10);
  const healthWeight = intEnv('HEALTH_WEIGHT_PERCENT', 5);

  const thresholds = {
    http_req_failed: [`rate<${errorRateMax}`],
    http_req_duration: [`p(95)<${p95Ms}`],
    'http_req_duration{flow:health}': ['p(95)<300'],
    'http_req_duration{flow:course-read}': [`p(95)<${p95Ms}`],
    'http_req_duration{flow:route-read}': [`p(95)<${intEnv('ROUTE_READ_P95_MS', 1000)}`],
    'http_req_duration{flow:weather-read}': [`p(95)<${intEnv('WEATHER_P95_MS', 1200)}`],
    'http_req_duration{flow:ride-policy}': [`p(95)<${intEnv('RIDE_POLICY_P95_MS', 1200)}`],
    'http_req_duration{flow:location-read}': [`p(95)<${intEnv('LOCATION_P95_MS', 500)}`],
    'http_req_duration{flow:write-core}': [`p(95)<${intEnv('WRITE_P95_MS', 1500)}`],
    checks: ['rate>0.99'],
  };

  if (SCENARIO === 'baseline' || SCENARIO === 'stress') {
    const totalVus = intEnv(
      SCENARIO === 'baseline' ? 'BASELINE_TOTAL_VUS' : 'STRESS_TOTAL_VUS',
      SCENARIO === 'baseline' ? 10 : 25,
    );

    const scenarios = {};
    addRampingScenario(scenarios, 'home', 'personaHomeDiscovery', {
      target: weightedVus(totalVus, homeWeight),
      rampUp: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_UP' : 'STRESS_RAMP_UP', SCENARIO === 'baseline' ? '2m' : '1m'),
      hold: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_HOLD' : 'STRESS_HOLD', SCENARIO === 'baseline' ? '5m' : '3m'),
      rampDown: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_DOWN' : 'STRESS_RAMP_DOWN', SCENARIO === 'baseline' ? '2m' : '1m'),
    });
    addRampingScenario(scenarios, 'preRide', 'personaPreRide', {
      target: weightedVus(totalVus, preRideWeight),
      rampUp: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_UP' : 'STRESS_RAMP_UP', SCENARIO === 'baseline' ? '2m' : '1m'),
      hold: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_HOLD' : 'STRESS_HOLD', SCENARIO === 'baseline' ? '5m' : '3m'),
      rampDown: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_DOWN' : 'STRESS_RAMP_DOWN', SCENARIO === 'baseline' ? '2m' : '1m'),
    });
    addRampingScenario(scenarios, 'inRide', 'personaInRide', {
      target: weightedVus(totalVus, inRideWeight),
      rampUp: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_UP' : 'STRESS_RAMP_UP', SCENARIO === 'baseline' ? '2m' : '1m'),
      hold: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_HOLD' : 'STRESS_HOLD', SCENARIO === 'baseline' ? '5m' : '3m'),
      rampDown: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_DOWN' : 'STRESS_RAMP_DOWN', SCENARIO === 'baseline' ? '2m' : '1m'),
    });
    addRampingScenario(scenarios, 'write', 'personaRideRecord', {
      target: weightedVus(totalVus, writeWeight),
      rampUp: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_UP' : 'STRESS_RAMP_UP', SCENARIO === 'baseline' ? '2m' : '1m'),
      hold: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_HOLD' : 'STRESS_HOLD', SCENARIO === 'baseline' ? '5m' : '3m'),
      rampDown: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_DOWN' : 'STRESS_RAMP_DOWN', SCENARIO === 'baseline' ? '2m' : '1m'),
    });
    addRampingScenario(scenarios, 'health', 'personaHealth', {
      target: weightedVus(totalVus, healthWeight),
      rampUp: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_UP' : 'STRESS_RAMP_UP', SCENARIO === 'baseline' ? '2m' : '1m'),
      hold: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_HOLD' : 'STRESS_HOLD', SCENARIO === 'baseline' ? '5m' : '3m'),
      rampDown: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_DOWN' : 'STRESS_RAMP_DOWN', SCENARIO === 'baseline' ? '2m' : '1m'),
    });

    return {
      scenarios,
      tags: {
        testid: TEST_ID,
        scenario_profile: SCENARIO,
      },
      thresholds,
    };
  }
  const scenarios = {};
  addSmokeScenario(scenarios, 'home', 'personaHomeDiscovery', 2);
  addSmokeScenario(scenarios, 'preRide', 'personaPreRide', 2);
  addSmokeScenario(scenarios, 'inRide', 'personaInRide', 2);
  addSmokeScenario(scenarios, 'write', 'personaRideRecord', 1);
  addSmokeScenario(scenarios, 'health', 'personaHealth', 2);

  return {
    scenarios,
    tags: {
      testid: TEST_ID,
      scenario_profile: SCENARIO,
    },
    thresholds,
  };
}

export const options = buildOptions();

function authHeaders() {
  const token = __ENV.AUTH_BEARER_TOKEN;
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function getJson(path, tags = {}, params = {}) {
  const merged = {
    tags: baseRequestTags(tags),
    headers: {
      Accept: 'application/json',
      ...authHeaders(),
      ...(params.headers || {}),
    },
  };

  return http.get(`${BASE_URL}${path}`, merged);
}

function postJson(path, body, tags = {}) {
  return http.post(`${BASE_URL}${path}`, JSON.stringify(body), {
    tags: baseRequestTags(tags),
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      ...authHeaders(),
    },
  });
}

function commonChecks(response, expectedStatus) {
  return check(response, {
    [`status is ${expectedStatus}`]: (r) => r.status === expectedStatus,
    'body is not empty': (r) => !!r.body,
  });
}

function runHealthCheck() {
  group('health', () => {
    const response = getJson('/health', { flow: 'health', endpoint: 'health' });
    commonChecks(response, 200);
  });
}

function extractCourseId(response) {
  if (!response || response.status !== 200) {
    return null;
  }

  try {
    const payload = JSON.parse(response.body);
    const data = payload?.data;
    if (Array.isArray(data?.courses) && data.courses.length > 0) {
      return data.courses[0]?.courseId ?? data.courses[0]?.id ?? null;
    }
    if (Array.isArray(data?.items) && data.items.length > 0) {
      return data.items[0]?.courseId ?? data.items[0]?.id ?? null;
    }
    if (Array.isArray(data) && data.length > 0) {
      return data[0]?.courseId ?? data[0]?.id ?? null;
    }
    return null;
  } catch (_) {
    return null;
  }
}

export function setup() {
  const explicitCourseId = __ENV.COURSE_ID;
  if (explicitCourseId) {
    return { courseId: explicitCourseId };
  }

  const headers = {
    Accept: 'application/json',
    ...authHeaders(),
  };

  const featured = http.get(`${BASE_URL}/api/v1/courses/featured`, {
    tags: baseRequestTags({ flow: 'setup', endpoint: 'courses-featured-setup' }),
    headers,
  });
  const featuredCourseId = extractCourseId(featured);
  if (featuredCourseId) {
    return { courseId: String(featuredCourseId) };
  }

  const courses = http.get(`${BASE_URL}/api/v1/courses?limit=1`, {
    tags: baseRequestTags({ flow: 'setup', endpoint: 'courses-list-setup' }),
    headers,
  });
  const listCourseId = extractCourseId(courses);
  return { courseId: listCourseId ? String(listCourseId) : null };
}

function resolvedCourseId(setupData) {
  return __ENV.COURSE_ID || setupData?.courseId || null;
}

function runCourseReads(setupData) {
  group('course reads', () => {
    const featured = getJson('/api/v1/courses/featured', {
      flow: 'course-read',
      endpoint: 'courses-featured',
    });
    commonChecks(featured, 200);

    const courses = getJson('/api/v1/courses?limit=10', {
      flow: 'course-read',
      endpoint: 'courses-list',
    });
    commonChecks(courses, 200);

    const courseId = resolvedCourseId(setupData);
    if (!courseId) {
      return;
    }

    const detail = getJson(`/api/v1/courses/${courseId}`, {
      flow: 'course-read',
      endpoint: 'course-detail',
    });
    commonChecks(detail, 200);
  });
}

function runRoutePointsRead(setupData) {
  const courseId = resolvedCourseId(setupData);
  if (!courseId) {
    return;
  }

  group('route points read', () => {
    const routePoints = getJson(`/api/v1/courses/${courseId}/route-points`, {
      flow: 'route-read',
      endpoint: 'course-route-points',
    });
    commonChecks(routePoints, 200);
  });
}

function runWeatherRead() {
  group('weather current', () => {
    const lat = stringEnv('WEATHER_LAT', '37.5665');
    const lon = stringEnv('WEATHER_LON', '126.9780');
    const response = getJson(`/api/v1/weather/current?lat=${lat}&lon=${lon}`, {
      flow: 'weather-read',
      endpoint: 'weather-current',
    });
    commonChecks(response, 200);
  });
}

function runRidePolicy(setupData) {
  const courseId = resolvedCourseId(setupData);
  if (!courseId) {
    return;
  }

  group('ride policy evaluate', () => {
    const response = postJson(
      `/api/v1/courses/${courseId}/ride-policy/evaluate`,
      {
        phase: stringEnv('RIDE_PHASE', 'PRE_START'),
        location: {
          lat: Number.parseFloat(stringEnv('RIDE_LAT', '37.5665')),
          lon: Number.parseFloat(stringEnv('RIDE_LON', '126.9780')),
          accuracyM: Number.parseFloat(stringEnv('RIDE_ACCURACY_M', '15')),
          capturedAt: new Date().toISOString(),
        },
      },
      {
        flow: 'ride-policy',
        endpoint: 'ride-policy-evaluate',
      },
    );

    commonChecks(response, 200);
  });
}

function runRecentLocation() {
  if (!__ENV.AUTH_BEARER_TOKEN) {
    return;
  }

  group('recent location', () => {
    const response = getJson('/api/v1/location/me/recent', {
      flow: 'location-read',
      endpoint: 'location-me-recent',
    });

    check(response, {
      'status is 200 or 404': (r) => r.status === 200 || r.status === 404,
    });
  });
}

function runRideRecordWrite() {
  if (!__ENV.AUTH_BEARER_TOKEN) {
    return;
  }

  const payload = {
    startedAt: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
    endedAt: new Date().toISOString(),
    visibility: 'PRIVATE',
    routePoints: [
      {
        latitude: Number.parseFloat(stringEnv('WRITE_START_LAT', '37.5665')),
        longitude: Number.parseFloat(stringEnv('WRITE_START_LON', '126.9780')),
        recordedAt: new Date(Date.now() - 60 * 1000).toISOString(),
      },
      {
        latitude: Number.parseFloat(stringEnv('WRITE_END_LAT', '37.5670')),
        longitude: Number.parseFloat(stringEnv('WRITE_END_LON', '126.9785')),
        recordedAt: new Date().toISOString(),
      },
    ],
  };

  group('ride record save', () => {
    const response = postJson('/api/v1/ride-records', payload, {
      flow: 'write-core',
      endpoint: 'ride-record-save',
    });

    check(response, {
      'status is 200 or 201': (r) => r.status === 200 || r.status === 201,
      'body is not empty': (r) => !!r.body,
    });
  });
}

export function coreJourney(setupData) {
  runHealthCheck();
  runCourseReads(setupData);
  runRoutePointsRead(setupData);
  runWeatherRead();
  runRidePolicy(setupData);
  runRecentLocation();
  runRideRecordWrite();
  sleep(Number.parseFloat(stringEnv('SLEEP_SECONDS', '1')));
}

export function personaHomeDiscovery(setupData) {
  runCourseReads(setupData);
  sleep(Number.parseFloat(stringEnv('SLEEP_SECONDS', '1')));
}

export function personaPreRide(setupData) {
  runCourseReads(setupData);
  runRoutePointsRead(setupData);
  runWeatherRead();
  runRidePolicy(setupData);
  sleep(Number.parseFloat(stringEnv('SLEEP_SECONDS', '1')));
}

export function personaInRide(setupData) {
  runRidePolicy(setupData);
  runWeatherRead();
  runRecentLocation();
  sleep(Number.parseFloat(stringEnv('SLEEP_SECONDS', '1')));
}

export function personaRideRecord() {
  runRideRecordWrite();
  sleep(Number.parseFloat(stringEnv('SLEEP_SECONDS', '1')));
}

export function personaHealth() {
  runHealthCheck();
  sleep(Number.parseFloat(stringEnv('SLEEP_SECONDS', '1')));
}

export function handleSummary(data) {
  return {
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
    [`${SUMMARY_DIR}/${TEST_ID}-summary.json`]: JSON.stringify(data, null, 2),
  };
}

function textSummary(data, { indent = '', enableColors = false } = {}) {
  const color = (value, _name) => value;
  const lines = [];
  const iterations = data.metrics.iterations?.values?.count ?? 0;
  const failedRate = data.metrics.http_req_failed?.values?.rate ?? 0;
  const p95 = data.metrics.http_req_duration?.values?.['p(95)'] ?? 0;
  const checksRate = data.metrics.checks?.values?.rate ?? 0;
  lines.push(`${indent}scenario_profile: ${SCENARIO}`);
  lines.push(`${indent}testid: ${TEST_ID}`);
  lines.push(`${indent}iterations: ${iterations}`);
  lines.push(`${indent}http_req_failed(rate): ${failedRate}`);
  lines.push(`${indent}http_req_duration(p95): ${p95} ms`);
  lines.push(`${indent}checks(rate): ${checksRate}`);
  return enableColors ? color(lines.join('\n'), 'cyan') : lines.join('\n');
}

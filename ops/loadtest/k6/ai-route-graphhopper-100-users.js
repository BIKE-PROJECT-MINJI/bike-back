import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || '').replace(/\/$/, '');
const TEST_ID = __ENV.TEST_ID || `bike-ai-route-${Date.now()}`;
const SUMMARY_PATH = __ENV.SUMMARY_PATH || `ops/loadtest/results/${TEST_ID}-summary.json`;
const RUN_DURATION = __ENV.RUN_DURATION || '2m';
const AI_ROUTE_VUS = numberEnv('AI_ROUTE_VUS', 34);
const FREE_RIDE_VUS = numberEnv('FREE_RIDE_VUS', 33);
const COURSE_FOLLOW_VUS = numberEnv('COURSE_FOLLOW_VUS', 33);
const COURSE_READY_MAX_ATTEMPTS = numberEnv('COURSE_READY_MAX_ATTEMPTS', 20);
const COURSE_READY_POLL_SECONDS = floatEnv('COURSE_READY_POLL_SECONDS', 0.1);

const endpointDuration = new Trend('bike_endpoint_duration', true);
const endpointFailureRate = new Rate('bike_endpoint_failure_rate');
const endpointDurationByName = {
  'ai-route-from-text': new Trend('bike_endpoint_ai_route_from_text_duration', true),
  'auth-register': new Trend('bike_endpoint_auth_register_duration', true),
  'course-create': new Trend('bike_endpoint_course_create_duration', true),
  'course-detail': new Trend('bike_endpoint_course_detail_duration', true),
  'course-route-points': new Trend('bike_endpoint_course_route_points_duration', true),
  'ride-policy-evaluate': new Trend('bike_endpoint_ride_policy_evaluate_duration', true),
  'ride-record-regenerate': new Trend('bike_endpoint_ride_record_regenerate_duration', true),
  'ride-record-save': new Trend('bike_endpoint_ride_record_save_duration', true),
  'ride-record-status': new Trend('bike_endpoint_ride_record_status_duration', true),
};
const courseFollowReadyWaitDuration = new Trend('course_follow_ready_wait_duration', true);
const courseFollowReadyPollAttempts = new Trend('course_follow_ready_poll_attempts');
const courseFollowReadyFailureRate = new Rate('course_follow_ready_failure_rate');

if (!BASE_URL) {
  throw new Error('BASE_URL is required. Example: BASE_URL=http://127.0.0.1:8080');
}

const scenarios = {};
if (AI_ROUTE_VUS > 0) {
  scenarios.ai_route_generation = {
    executor: 'constant-vus',
    exec: 'aiRouteGeneration',
    vus: AI_ROUTE_VUS,
    duration: RUN_DURATION,
  };
}
if (FREE_RIDE_VUS > 0) {
  scenarios.free_ride_recording = {
    executor: 'constant-vus',
    exec: 'freeRideRecording',
    vus: FREE_RIDE_VUS,
    duration: RUN_DURATION,
  };
}
if (COURSE_FOLLOW_VUS > 0) {
  scenarios.course_follow_reading = {
    executor: 'constant-vus',
    exec: 'courseFollowReading',
    vus: COURSE_FOLLOW_VUS,
    duration: RUN_DURATION,
  };
}
if (Object.keys(scenarios).length === 0) {
  throw new Error('At least one of AI_ROUTE_VUS, FREE_RIDE_VUS, COURSE_FOLLOW_VUS must be greater than 0.');
}

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios,
  thresholds: {
    http_req_failed: ['rate<0.05'],
    checks: ['rate>0.95'],
    http_req_duration: ['p(95)<30000', 'p(99)<60000'],
    'http_req_duration{flow:ai-route}': ['p(95)<60000', 'p(99)<90000'],
    'http_req_duration{flow:free-ride}': ['p(95)<5000', 'p(99)<10000'],
    'http_req_duration{flow:course-follow}': ['p(95)<5000', 'p(99)<10000'],
  },
  tags: {
    testid: TEST_ID,
  },
};

export function aiRouteGeneration() {
  const token = registerUser('ai');
  group('ai route generation', () => {
    const response = postJson('/api/v1/ai-routes/plan/from-text', {
      lat: 37.4812,
      lon: 126.9527,
      text: routePromptFor(__VU + __ITER),
    }, token, { flow: 'ai-route', endpoint: 'ai-route-from-text' });

    check(response, {
      'ai route status is 200': (r) => r.status === 200,
      'ai route has route points': (r) => routePointCount(r) >= 2,
      'ai route has elevation summary': (r) => hasJsonPath(r, ['data', 'elevationSummary']),
    });
  });
  sleep(numberEnv('SLEEP_SECONDS', 1));
}

export function freeRideRecording() {
  const token = registerUser('ride');
  group('free ride save and regenerate', () => {
    const save = saveRideRecord(token, 'free');
    const rideRecordId = jsonValue(save, ['data', 'rideRecordId']);
    check(save, {
      'ride record save status is 200': (r) => r.status === 200,
      'ride record id exists': () => !!rideRecordId,
    });

    if (rideRecordId) {
      const regenerate = postJson(`/api/v1/ride-records/${rideRecordId}/regenerate`, {}, token, {
        flow: 'free-ride',
        endpoint: 'ride-record-regenerate',
      });
      check(regenerate, {
        'regenerate status is 200': (r) => r.status === 200,
      });
    }
  });
  sleep(numberEnv('SLEEP_SECONDS', 1));
}

export function courseFollowReading() {
  const token = registerUser('course');
  group('course follow read path', () => {
    const save = saveRideRecord(token, 'course', 'course-follow');
    const rideRecordId = jsonValue(save, ['data', 'rideRecordId']);
    const rideRecordReady = rideRecordId ? waitForRideRecordReady(rideRecordId, token) : false;
    const create = rideRecordId && rideRecordReady
      ? postJson('/api/v1/courses', {
        sourceRideRecordId: rideRecordId,
        name: `k6 course ${__VU}-${__ITER}`,
        description: 'k6 generated follow-path course',
        visibility: 'PUBLIC',
      }, token, { flow: 'course-follow', endpoint: 'course-create' })
      : null;
    const courseId = create ? jsonValue(create, ['data', 'courseId']) : null;

    check({ rideRecordReady, courseId }, {
      'ride record finalized READY': (value) => value.rideRecordReady,
      'course id exists': (value) => !!value.courseId,
    });

    if (courseId) {
      check(getJson(`/api/v1/courses/${courseId}`, null, { flow: 'course-follow', endpoint: 'course-detail' }), {
        'course detail status is 200': (r) => r.status === 200,
      });
      check(getJson(`/api/v1/courses/${courseId}/route-points`, null, {
        flow: 'course-follow',
        endpoint: 'course-route-points',
      }), {
        'route points status is 200': (r) => r.status === 200,
      });
      check(postJson(`/api/v1/courses/${courseId}/ride-policy/evaluate`, ridePolicyPayload(), null, {
        flow: 'course-follow',
        endpoint: 'ride-policy-evaluate',
      }), {
        'ride policy status is 200': (r) => r.status === 200,
      });
    }
  });
  sleep(numberEnv('SLEEP_SECONDS', 1));
}

function registerUser(prefix) {
  const email = `${TEST_ID}-${prefix}-${__VU}-${__ITER}@load.local`;
  const response = postJson('/api/v1/auth/register', {
    email,
    password: 'load-test-password',
    displayName: `${prefix}-${__VU}`,
    profileImageUrl: null,
    legacyExternalId: null,
  }, null, { flow: 'auth', endpoint: 'auth-register' });
  check(response, {
    'register status is 200': (r) => r.status === 200,
  });
  return jsonValue(response, ['data', 'accessToken']) || '';
}

function saveRideRecord(token, prefix, flow) {
  const now = Date.now();
  return postJson('/api/v1/ride-records', {
    clientRideId: `${TEST_ID}-${prefix}-${__VU}-${__ITER}`,
    startedAt: new Date(now - 90000).toISOString(),
    endedAt: new Date(now).toISOString(),
    summary: {
      distanceM: 820,
      durationSec: 90,
    },
    routePoints: [
      ridePoint(1, 37.4812, 126.9527, now - 90000, 0),
      ridePoint(2, 37.4824, 126.9553, now - 45000, 410),
      ridePoint(3, 37.4840, 126.9584, now, 820),
    ],
  }, token, { flow: flow || 'free-ride', endpoint: 'ride-record-save' });
}

function waitForRideRecordReady(rideRecordId, token) {
  const startedAt = Date.now();
  let attempts = 0;
  let lastStatus = 'UNKNOWN';
  let ready = false;

  for (let attempt = 1; attempt <= COURSE_READY_MAX_ATTEMPTS; attempt += 1) {
    attempts = attempt;
    const response = getJson(`/api/v1/ride-records/${rideRecordId}`, token, {
      flow: 'course-follow',
      endpoint: 'ride-record-status',
    });
    lastStatus = jsonValue(response, ['data', 'status']) || 'UNKNOWN';

    check(response, {
      'ride record status fetch is 200': (r) => r.status === 200,
    });

    if (response.status === 200 && lastStatus === 'READY') {
      ready = true;
      break;
    }

    if (response.status === 200 && lastStatus === 'FAILED') {
      break;
    }

    if (attempt < COURSE_READY_MAX_ATTEMPTS) {
      sleep(COURSE_READY_POLL_SECONDS);
    }
  }

  const tags = {
    flow: 'course-follow',
    endpoint: 'ride-record-status',
    finalization_status: lastStatus,
  };
  courseFollowReadyWaitDuration.add(Date.now() - startedAt, tags);
  courseFollowReadyPollAttempts.add(attempts, tags);
  courseFollowReadyFailureRate.add(!ready, tags);

  return ready;
}

function ridePoint(pointOrder, latitude, longitude, capturedAt, progressM) {
  return {
    pointOrder,
    latitude,
    longitude,
    capturedAt: new Date(capturedAt).toISOString(),
    accuracyM: 12,
    speedMps: 4.2,
    bearingDeg: 82,
    altitudeM: 42,
    distanceToRouteM: 4,
    routeProgressPct: Math.min(100, Math.round((progressM / 820) * 100)),
  };
}

function ridePolicyPayload() {
  const capturedAt = new Date().toISOString();
  return {
    phase: 'ACTIVE',
    location: { lat: 37.4824, lon: 126.9553, accuracyM: 12, capturedAt },
    trace: [
      { lat: 37.4812, lon: 126.9527, accuracyM: 12, capturedAt },
      { lat: 37.4824, lon: 126.9553, accuracyM: 12, capturedAt },
    ],
  };
}

function postJson(path, body, token, tags) {
  const response = http.post(`${BASE_URL}${path}`, JSON.stringify(body), requestParams(token, tags));
  recordEndpointMetrics(response, 'POST', tags);
  return response;
}

function getJson(path, token, tags) {
  const response = http.get(`${BASE_URL}${path}`, requestParams(token, tags));
  recordEndpointMetrics(response, 'GET', tags);
  return response;
}

function requestParams(token, tags) {
  const headers = { Accept: 'application/json', 'Content-Type': 'application/json' };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return { headers, tags: requestTags(tags) };
}

function requestTags(tags) {
  return Object.assign({ testid: TEST_ID }, tags || {});
}

function recordEndpointMetrics(response, method, tags) {
  const metricTags = Object.assign({ method }, requestTags(tags));
  endpointDuration.add(response.timings.duration, metricTags);
  endpointFailureRate.add(response.status === 0 || response.status >= 400, metricTags);
  const namedTrend = endpointDurationByName[tags && tags.endpoint];
  if (namedTrend) {
    namedTrend.add(response.timings.duration, metricTags);
  }
}

function routePromptFor(seed) {
  const prompts = [
    '서울대입구에서 부천역까지 풍경 좋고 도로 정비가 잘 된 경로 추천',
    '서울대입구 기준 안양천 합수부까지 평지 위주로 시원한 강변 코스 추천',
    '서울대입구 근처에서 남산 국립극장 쪽 업힐 훈련 코스 추천',
  ];
  return prompts[seed % prompts.length];
}

function routePointCount(response) {
  const value = jsonValue(response, ['data', 'routePoints']);
  return Array.isArray(value) ? value.length : 0;
}

function hasJsonPath(response, path) {
  return jsonValue(response, path) !== null;
}

function jsonValue(response, path) {
  try {
    return path.reduce((current, key) => (current === undefined || current === null ? null : current[key]), JSON.parse(response.body));
  } catch (_) {
    return null;
  }
}

function numberEnv(name, fallback) {
  const value = Number.parseInt(__ENV[name] || '', 10);
  return Number.isNaN(value) ? fallback : value;
}

function floatEnv(name, fallback) {
  const value = Number.parseFloat(__ENV[name] || '');
  return Number.isNaN(value) ? fallback : value;
}

export function handleSummary(data) {
  return {
    stdout: summaryText(data),
    [SUMMARY_PATH]: JSON.stringify(data, null, 2),
  };
}

function summaryText(data) {
  const duration = data.metrics.http_req_duration && data.metrics.http_req_duration.values;
  const failed = data.metrics.http_req_failed && data.metrics.http_req_failed.values;
  return [
    `testid: ${TEST_ID}`,
    `vus: ${AI_ROUTE_VUS + FREE_RIDE_VUS + COURSE_FOLLOW_VUS}`,
    `http_req_failed(rate): ${failed ? failed.rate : 'n/a'}`,
    `http_req_duration(p95): ${duration ? duration['p(95)'] : 'n/a'} ms`,
    `http_req_duration(p99): ${duration ? duration['p(99)'] : 'n/a'} ms`,
  ].join('\n');
}

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || '').replace(/\/$/, '');
const TEST_ID = __ENV.TEST_ID || `bike-ai-route-${Date.now()}`;
const SUMMARY_PATH = __ENV.SUMMARY_PATH || `ops/loadtest/results/${TEST_ID}-summary.json`;
const RUN_DURATION = __ENV.RUN_DURATION || '2m';
const AI_ROUTE_VUS = numberEnv('AI_ROUTE_VUS', 25);
const AI_ROUTE_ITERATIONS_PER_VU = numberEnv('AI_ROUTE_ITERATIONS_PER_VU', 3);
const COURSE_MAP_READ_VUS = numberEnv('COURSE_MAP_READ_VUS', 35);
const COURSE_FOLLOW_VUS = numberEnv('COURSE_FOLLOW_VUS', 30);
const FREE_RIDE_VUS = numberEnv('FREE_RIDE_VUS', 10);
const RIDE_FINALIZATION_VUS = numberEnv('RIDE_FINALIZATION_VUS', 0);
const COURSE_READY_MAX_ATTEMPTS = numberEnv('COURSE_READY_MAX_ATTEMPTS', 80);
const COURSE_READY_POLL_SECONDS = floatEnv('COURSE_READY_POLL_SECONDS', 0.1);
const PROMOTE_AI_COURSE_RATE = floatEnv('PROMOTE_AI_COURSE_RATE', 0.2);
const SETUP_AUTH_POOL_ENABLED = boolEnv('SETUP_AUTH_POOL_ENABLED', true);
const SETUP_AUTH_EXTRA_TOKENS = numberEnv('SETUP_AUTH_EXTRA_TOKENS', 2);
const RIDE_FINALIZATION_REQUIRE_READY = boolEnv('RIDE_FINALIZATION_REQUIRE_READY', false);
const RIDE_FINALIZATION_READY_FAILURE_THRESHOLD = __ENV.RIDE_FINALIZATION_READY_FAILURE_THRESHOLD || '';
const ERROR_RATE_MAX = __ENV.ERROR_RATE_MAX || '0.01';
const ALLOW_HIGH_VUS = boolEnv('ALLOW_HIGH_VUS', false);
const PROVIDER_MODE = __ENV.PROVIDER_MODE || 'unspecified';
const TOTAL_VUS = AI_ROUTE_VUS
  + COURSE_MAP_READ_VUS
  + COURSE_FOLLOW_VUS
  + FREE_RIDE_VUS
  + RIDE_FINALIZATION_VUS;

if (TOTAL_VUS > 25 && !ALLOW_HIGH_VUS) {
  throw new Error(`total VUs ${TOTAL_VUS} exceeds the 25 VU approval gate; set ALLOW_HIGH_VUS=true only after explicit approval`);
}

const endpointDuration = new Trend('bike_endpoint_duration', true);
const endpointFailureRate = new Rate('bike_endpoint_failure_rate');
const aiRouteRateLimitedRate = new Rate('ai_route_rate_limited_rate');
const endpointDurationByName = {
  'ai-route-session-create': new Trend('bike_endpoint_ai_route_session_create_duration', true),
  'ai-route-session-get': new Trend('bike_endpoint_ai_route_session_get_duration', true),
  'ai-route-candidate-promote': new Trend('bike_endpoint_ai_route_candidate_promote_duration', true),
  'auth-register': new Trend('bike_endpoint_auth_register_duration', true),
  'course-list': new Trend('bike_endpoint_course_list_duration', true),
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
const rideFinalizationReadyWaitDuration = new Trend('ride_finalization_ready_wait_duration', true);
const rideFinalizationReadyPollAttempts = new Trend('ride_finalization_ready_poll_attempts');
const rideFinalizationReadyFailureRate = new Rate('ride_finalization_ready_failure_rate');
const rideSaveBusyRate = new Rate('ride_save_busy_rate');
const rideSaveBusyRetryAfterSeconds = new Trend('ride_save_busy_retry_after_seconds');

if (!BASE_URL) {
  throw new Error('BASE_URL is required. Example: BASE_URL=http://127.0.0.1:8080');
}

const scenarios = {};
if (AI_ROUTE_VUS > 0) {
  scenarios.ai_route_generation = {
    executor: 'per-vu-iterations',
    exec: 'aiRouteGeneration',
    vus: AI_ROUTE_VUS,
    iterations: AI_ROUTE_ITERATIONS_PER_VU,
    maxDuration: RUN_DURATION,
  };
}
if (COURSE_MAP_READ_VUS > 0) {
  scenarios.course_map_reading = {
    executor: 'constant-vus',
    exec: 'courseMapReading',
    vus: COURSE_MAP_READ_VUS,
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
if (RIDE_FINALIZATION_VUS > 0) {
  scenarios.ride_finalization_to_course = {
    executor: 'constant-vus',
    exec: 'rideFinalizationToCourse',
    vus: RIDE_FINALIZATION_VUS,
    duration: RUN_DURATION,
  };
}
if (Object.keys(scenarios).length === 0) {
  throw new Error('At least one scenario VU env must be greater than 0.');
}

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios,
  thresholds: buildThresholds(),
  tags: {
    testid: TEST_ID,
  },
};

function buildThresholds() {
  const thresholds = {
    http_req_failed: [`rate<${ERROR_RATE_MAX}`],
    checks: ['rate>0.99'],
    http_req_duration: ['p(95)<30000', 'p(99)<60000'],
    'http_req_duration{flow:ai-route}': ['p(95)<60000', 'p(99)<90000'],
    'http_req_duration{flow:course-map-read}': ['p(95)<5000', 'p(99)<10000'],
    'http_req_duration{flow:free-ride}': ['p(95)<5000', 'p(99)<10000'],
    'http_req_duration{flow:course-follow}': ['p(95)<5000', 'p(99)<10000'],
    'http_req_duration{flow:ride-finalization}': ['p(95)<15000', 'p(99)<30000'],
  };
  if (RIDE_FINALIZATION_REQUIRE_READY) {
    thresholds.ride_finalization_ready_failure_rate = [
      `rate<${RIDE_FINALIZATION_READY_FAILURE_THRESHOLD || '0.01'}`,
    ];
  }
  return thresholds;
}

export function setup() {
  if (!SETUP_AUTH_POOL_ENABLED) {
    return { tokens: {} };
  }
  return {
    tokens: {
      shared: createTokenPool('shared', TOTAL_VUS),
    },
  };
}

export function aiRouteGeneration(data) {
  const token = tokenFor(data, 'ai') || registerUser('ai');
  group('ai route generation', () => {
    const response = postJson('/api/v1/ai-route-sessions', {
      lat: 37.4812,
      lon: 126.9527,
      destinationLat: 37.5512,
      destinationLon: 126.9882,
      destinationLabel: '남산',
      rideStyle: 'SCENERY_FIRST',
      elevationPreference: elevationPreferenceFor(__VU + __ITER),
      text: routePromptFor(__VU + __ITER),
    }, token, { flow: 'ai-route', endpoint: 'ai-route-session-create' });

    const sessionId = jsonValue(response, ['data', 'sessionId']);
    const candidateId = jsonValue(response, ['data', 'candidates', 0, 'candidateId']);
    aiRouteRateLimitedRate.add(response.status === 429, {
      testid: TEST_ID,
      flow: 'ai-route',
      endpoint: 'ai-route-session-create',
    });
    check(response, {
      'ai route session status is 200 or protected 429': (r) => r.status === 200 || r.status === 429,
      'ai route session has candidate when accepted': (r) => r.status === 429 || routeCandidateCount(r) >= 1,
      'ai route candidate exposes evidence status when accepted': (r) => {
        if (r.status === 429) return true;
        const candidate = jsonValue(r, ['data', 'candidates', 0]);
        return !!candidate
          && typeof candidate.elevationStatus === 'string'
          && typeof candidate.sceneryEvidenceStatus === 'string'
          && Array.isArray(candidate.evidenceBadges)
          && candidate.routingMetadata !== null;
      },
    });

    if (response.status === 200 && sessionId) {
      check(getJson(`/api/v1/ai-route-sessions/${sessionId}`, token, {
        flow: 'ai-route',
        endpoint: 'ai-route-session-get',
      }), {
        'ai route session get status is 200': (r) => r.status === 200,
      });
    }

    if (response.status === 200 && sessionId && candidateId && Math.random() < PROMOTE_AI_COURSE_RATE) {
      check(postJson(`/api/v1/ai-route-sessions/${sessionId}/candidates/${candidateId}/course`, {
        name: `k6 ai course ${__VU}-${__ITER}`,
        description: 'k6 promoted AI route candidate',
        visibility: 'PRIVATE',
      }, token, { flow: 'ai-route', endpoint: 'ai-route-candidate-promote' }), {
        'ai route candidate promote status is 200': (r) => r.status === 200,
      });
    }
  });
  sleep(numberEnv('SLEEP_SECONDS', 1));
}

export function courseMapReading() {
  group('course map reading', () => {
    const list = getJson('/api/v1/courses?limit=10', null, {
      flow: 'course-map-read',
      endpoint: 'course-list',
    });
    const courseId = extractCourseId(list);
    check(list, {
      'course list status is 200': (r) => r.status === 200,
    });

    if (courseId) {
      check(getJson(`/api/v1/courses/${courseId}`, null, {
        flow: 'course-map-read',
        endpoint: 'course-detail',
      }), {
        'course detail read status is 200': (r) => r.status === 200,
      });
      check(getJson(`/api/v1/courses/${courseId}/route-points`, null, {
        flow: 'course-map-read',
        endpoint: 'course-route-points',
      }), {
        'course route points read status is 200': (r) => r.status === 200,
      });
    }
  });
  sleep(numberEnv('SLEEP_SECONDS', 1));
}

export function freeRideRecording(data) {
  const token = tokenFor(data, 'ride') || registerUser('ride');
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

export function courseFollowReading(data) {
  group('course follow read and hud', () => {
    const courseId = readFirstCourseId('course-follow');

    check({ courseId }, {
      'course id exists for follow': (value) => !!value.courseId,
    });

    if (courseId) {
      readCourseDetailRouteAndHud(courseId, 'course-follow');
    }
  });
  sleep(numberEnv('SLEEP_SECONDS', 1));
}

export function rideFinalizationToCourse(data) {
  const token = tokenFor(data, 'finalize') || registerUser('finalize');
  group('ride finalization to course', () => {
    const save = saveRideRecord(token, 'finalize', 'ride-finalization');
    const saveAccepted = save.status === 200;
    const saveBusy = isRideSaveBusy(save);
    const rideRecordId = jsonValue(save, ['data', 'rideRecordId']);
    const rideRecordReady = rideRecordId && saveAccepted
      ? waitForRideRecordReady(rideRecordId, token, 'ride-finalization')
      : false;
    const create = rideRecordId && rideRecordReady
      ? postJson('/api/v1/courses', {
        sourceRideRecordId: rideRecordId,
        name: `k6 finalized course ${__VU}-${__ITER}`,
        description: 'k6 generated course from finalized ride record',
        visibility: 'PUBLIC',
      }, token, { flow: 'ride-finalization', endpoint: 'course-create' })
      : null;
    const courseId = create ? jsonValue(create, ['data', 'courseId']) : null;

    check({ saveAccepted, saveBusy, rideRecordId, rideRecordReady, courseId }, {
      'ride save accepted or controlled busy': (value) => value.saveAccepted || value.saveBusy,
      'ride record id exists when save accepted': (value) => !value.saveAccepted || !!value.rideRecordId,
      'ride record finalized READY when required': (value) => (
        !RIDE_FINALIZATION_REQUIRE_READY || value.rideRecordReady
      ),
      'course id exists when finalized': (value) => !value.rideRecordReady || !!value.courseId,
    });
  });
  sleep(numberEnv('SLEEP_SECONDS', 1));
}

export function legacyCourseFollowWriting(data) {
  const token = tokenFor(data, 'course') || registerUser('course');
  group('course follow read path', () => {
    const save = saveRideRecord(token, 'course', 'course-follow');
    const rideRecordId = jsonValue(save, ['data', 'rideRecordId']);
    const rideRecordReady = rideRecordId ? waitForRideRecordReady(rideRecordId, token, 'course-follow') : false;
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
      readCourseDetailRouteAndHud(courseId, 'course-follow');
    }
  });
  sleep(numberEnv('SLEEP_SECONDS', 1));
}

function readFirstCourseId(flow) {
  const list = getJson('/api/v1/courses?limit=10', null, {
    flow,
    endpoint: 'course-list',
  });
  const courseId = extractCourseId(list);
  check(list, {
    [`${flow} course list status is 200`]: (r) => r.status === 200,
  });
  return courseId;
}

function readCourseDetailRouteAndHud(courseId, flow) {
  check(getJson(`/api/v1/courses/${courseId}`, null, { flow, endpoint: 'course-detail' }), {
    [`${flow} course detail status is 200`]: (r) => r.status === 200,
  });
  check(getJson(`/api/v1/courses/${courseId}/route-points`, null, {
    flow,
    endpoint: 'course-route-points',
  }), {
    [`${flow} route points status is 200`]: (r) => r.status === 200,
  });
  check(postJson(`/api/v1/courses/${courseId}/ride-policy/evaluate`, ridePolicyPayload(), null, {
    flow,
    endpoint: 'ride-policy-evaluate',
  }), {
    [`${flow} ride policy status is 200`]: (r) => r.status === 200,
  });
}

function createTokenPool(prefix, vus) {
  const size = Math.max(0, vus + SETUP_AUTH_EXTRA_TOKENS);
  const tokens = [];
  for (let index = 0; index < size; index += 1) {
    const token = registerSetupUser(prefix, index);
    if (token) {
      tokens.push(token);
    }
  }
  if (tokens.length < vus) {
    throw new Error(`failed to prepare enough ${prefix} auth tokens: expected=${vus}, actual=${tokens.length}`);
  }
  return tokens;
}

function tokenFor(data, prefix) {
  const tokens = data && data.tokens && (data.tokens.shared || data.tokens[prefix]);
  if (!Array.isArray(tokens) || tokens.length === 0) {
    return '';
  }
  return tokens[__VU - 1] || '';
}

function registerSetupUser(prefix, index) {
  const email = `${TEST_ID}-setup-${prefix}-${index}@load.local`;
  const response = http.post(`${BASE_URL}/api/v1/auth/register`, JSON.stringify({
    email,
    password: 'load-test-password',
    displayName: `${prefix}-setup-${index}`,
    profileImageUrl: null,
    legacyExternalId: null,
  }), {
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    tags: requestTags({ flow: 'setup-auth', endpoint: 'auth-register-setup' }),
  });
  if (response.status !== 200) {
    throw new Error(`setup auth register failed prefix=${prefix} index=${index} status=${response.status} body=${response.body}`);
  }
  return jsonValue(response, ['data', 'accessToken']) || '';
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

function waitForRideRecordReady(rideRecordId, token, flow) {
  const startedAt = Date.now();
  let attempts = 0;
  let lastStatus = 'UNKNOWN';
  let ready = false;

  for (let attempt = 1; attempt <= COURSE_READY_MAX_ATTEMPTS; attempt += 1) {
    attempts = attempt;
    const response = getJson(`/api/v1/ride-records/${rideRecordId}`, token, {
      flow,
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
    flow,
    endpoint: 'ride-record-status',
    finalization_status: lastStatus,
  };
  if (flow === 'course-follow') {
    courseFollowReadyWaitDuration.add(Date.now() - startedAt, tags);
    courseFollowReadyPollAttempts.add(attempts, tags);
    courseFollowReadyFailureRate.add(!ready, tags);
  } else {
    rideFinalizationReadyWaitDuration.add(Date.now() - startedAt, tags);
    rideFinalizationReadyPollAttempts.add(attempts, tags);
    rideFinalizationReadyFailureRate.add(!ready, tags);
  }

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
  endpointFailureRate.add(isEndpointFailure(response, tags), metricTags);
  recordRideSaveBusy(response, tags, metricTags);
  const namedTrend = endpointDurationByName[tags && tags.endpoint];
  if (namedTrend) {
    namedTrend.add(response.timings.duration, metricTags);
  }
}

function recordRideSaveBusy(response, tags, metricTags) {
  if (!tags || tags.endpoint !== 'ride-record-save') {
    return;
  }
  const busy = isRideSaveBusy(response);
  rideSaveBusyRate.add(busy, metricTags);
  if (busy) {
    rideSaveBusyRetryAfterSeconds.add(numberHeader(response, 'Retry-After'), metricTags);
  }
}

function isEndpointFailure(response, tags) {
  return response.status === 0 || response.status >= 400;
}

function isRideSaveBusy(response) {
  return response.status === 503 && jsonValue(response, ['data', 'errorCode']) === 'RIDE_SAVE_BUSY';
}

function numberHeader(response, name) {
  const value = Number.parseInt(response.headers[name] || response.headers[name.toLowerCase()] || '', 10);
  return Number.isNaN(value) ? 0 : value;
}

function routePromptFor(seed) {
  const prompts = [
    '서울대입구에서 부천역까지 풍경 좋고 도로 정비가 잘 된 경로 추천',
    '서울대입구 기준 안양천 합수부까지 평지 위주로 시원한 강변 코스 추천',
    '서울대입구 근처에서 남산 국립극장 쪽 업힐 훈련 코스 추천',
  ];
  return prompts[seed % prompts.length];
}

function elevationPreferenceFor(seed) {
  const preferences = ['FLAT_FIRST', 'CLIMB_FIRST', 'BALANCED'];
  return preferences[seed % preferences.length];
}

function routeCandidateCount(response) {
  const value = jsonValue(response, ['data', 'candidates']);
  return Array.isArray(value) ? value.length : 0;
}

function extractCourseId(response) {
  const data = jsonValue(response, ['data']);
  const candidates = [
    data && data.courses,
    data && data.items,
    Array.isArray(data) ? data : null,
  ].filter((value) => Array.isArray(value) && value.length > 0);
  if (candidates.length === 0) {
    return null;
  }
  const first = candidates[0][0];
  return first && (first.courseId || first.id);
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

function boolEnv(name, fallback) {
  const value = (__ENV[name] || '').trim().toLowerCase();
  if (value === 'true' || value === '1' || value === 'yes') {
    return true;
  }
  if (value === 'false' || value === '0' || value === 'no') {
    return false;
  }
  return fallback;
}

export function handleSummary(data) {
  data.bike_metadata = {
    test_id: TEST_ID,
    provider_mode: PROVIDER_MODE,
    total_vus: TOTAL_VUS,
    high_vus_approved: ALLOW_HIGH_VUS,
    error_rate_max: ERROR_RATE_MAX,
    ai_route_iterations_per_vu: AI_ROUTE_ITERATIONS_PER_VU,
    vus: {
      ai_route: AI_ROUTE_VUS,
      course_map_read: COURSE_MAP_READ_VUS,
      course_follow: COURSE_FOLLOW_VUS,
      free_ride: FREE_RIDE_VUS,
      ride_finalization: RIDE_FINALIZATION_VUS,
    },
  };
  const sanitizedData = sanitizedSummaryData(data);
  return {
    stdout: summaryText(data),
    [SUMMARY_PATH]: JSON.stringify(sanitizedData, null, 2),
  };
}

function sanitizedSummaryData(data) {
  const sanitized = JSON.parse(JSON.stringify(data));
  if (sanitized.setup_data && sanitized.setup_data.tokens) {
    sanitized.setup_data.tokens = '[REDACTED]';
  }
  return sanitized;
}

function summaryText(data) {
  const duration = data.metrics.http_req_duration && data.metrics.http_req_duration.values;
  const failed = data.metrics.http_req_failed && data.metrics.http_req_failed.values;
  return [
    `testid: ${TEST_ID}`,
    `vus: ${TOTAL_VUS}`,
    `provider_mode: ${PROVIDER_MODE}`,
    `http_req_failed(rate): ${failed ? failed.rate : 'n/a'}`,
    `http_req_duration(p95): ${duration ? duration['p(95)'] : 'n/a'} ms`,
    `http_req_duration(p99): ${duration ? duration['p(99)'] : 'n/a'} ms`,
  ].join('\n');
}

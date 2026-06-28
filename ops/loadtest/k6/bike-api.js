import http from 'k6/http';
import { check, group, sleep } from 'k6';

var BASE_URL = (__ENV.BASE_URL || '').replace(/\/$/, '');
var SCENARIO = __ENV.SCENARIO || 'smoke';
var ACTIVE_PERSONAS = parsePersonaFilter(__ENV.PERSONAS || 'home,profile,preRide,inRide,write,health');
var TEST_ID = __ENV.TEST_ID || 'bike-' + SCENARIO;
var SUMMARY_DIR = (__ENV.SUMMARY_DIR || 'ops/loadtest/results').replace(/\/$/, '');
var ENABLE_WEATHER_READ = ((__ENV.ENABLE_WEATHER_READ || 'true') + '').toLowerCase() !== 'false';
var SLOW_REQUEST_SAMPLE_MS = intEnv('SLOW_REQUEST_SAMPLE_MS', 1000);
var WRITE_ROUTE_POINT_COUNT = Math.max(2, intEnv('WRITE_ROUTE_POINT_COUNT', 2));
var WRITE_POLL_FINALIZATION = ((__ENV.WRITE_POLL_FINALIZATION || 'false') + '').toLowerCase() === 'true';
var WRITE_FINALIZATION_POLL_ATTEMPTS = Math.max(1, intEnv('WRITE_FINALIZATION_POLL_ATTEMPTS', 5));
var WRITE_FINALIZATION_POLL_INTERVAL_SECONDS = Number.parseFloat(__ENV.WRITE_FINALIZATION_POLL_INTERVAL_SECONDS || '1');

if (!BASE_URL) {
  throw new Error('BASE_URL 환경변수는 필수입니다. 예: BASE_URL=http://localhost:8080');
}

function intEnv(name, fallback) {
  var raw = __ENV[name];
  if (!raw) return fallback;
  var parsed = Number.parseInt(raw, 10);
  return Number.isNaN(parsed) ? fallback : parsed;
}

function stringEnv(name, fallback) {
  return __ENV[name] || fallback;
}

function parsePersonaFilter(raw) {
  return raw
    .split(',')
    .map(function(value) { return value.trim(); })
    .filter(Boolean);
}

function isPersonaEnabled(name) {
  if ((name === 'profile' || name === 'write') && !canRunAuthenticatedPersona()) {
    return false;
  }
  return ACTIVE_PERSONAS.indexOf(name) !== -1;
}

function canRunAuthenticatedPersona() {
  return !!__ENV.AUTH_BEARER_TOKEN || ((__ENV.AUTH_AUTO_REGISTER || 'false') + '').toLowerCase() === 'true';
}

function baseRequestTags(extra) {
  extra = extra || {};
  var result = { testid: TEST_ID, scenario_profile: SCENARIO };
  Object.keys(extra).forEach(function(key) {
    result[key] = extra[key];
  });
  return result;
}

function generateCorrelationId() {
  var iteration = typeof __ITER === 'undefined' ? 0 : __ITER;
  return [
    TEST_ID.replace(/[^a-zA-Z0-9._:-]/g, '-'),
    'vu' + (__VU || 0),
    'iter' + iteration,
    Math.random().toString(16).slice(2, 10),
  ].join('-');
}

function responseHeader(response, name) {
  if (!response || !response.headers) {
    return '';
  }
  var expected = name.toLowerCase();
  var keys = Object.keys(response.headers);
  for (var i = 0; i < keys.length; i += 1) {
    if (keys[i].toLowerCase() === expected) {
      return response.headers[keys[i]];
    }
  }
  return '';
}

function withCorrelationHeaders(headers, requestId, traceId) {
  var merged = {};
  Object.keys(headers || {}).forEach(function(key) {
    merged[key] = headers[key];
  });
  merged['X-Request-Id'] = requestId;
  merged['X-Trace-Id'] = traceId;
  return merged;
}

function logSlowRequest(response, method, path, endpoint, requestId, traceId) {
  var durationMs = response && response.timings ? response.timings.duration : 0;
  if (durationMs < SLOW_REQUEST_SAMPLE_MS) {
    return;
  }
  console.warn(JSON.stringify({
    type: 'slow_request_sample',
    test_id: TEST_ID,
    scenario: SCENARIO,
    vu: __VU || 0,
    iter: __ITER || 0,
    method: method,
    path: path,
    endpoint: endpoint || 'unknown',
    status: response ? response.status : 0,
    duration_ms: durationMs,
    request_id: requestId,
    response_request_id: responseHeader(response, 'X-Request-Id'),
    trace_id: traceId,
    response_trace_id: responseHeader(response, 'X-Trace-Id'),
  }));
}

function allocateWeightedVus(total, weights) {
  var result = {};
  var active = [];
  Object.keys(weights).forEach(function(name) {
    result[name] = 0;
    if (isPersonaEnabled(name) && weights[name] > 0) {
      active.push({ name: name, weight: weights[name] });
    }
  });
  if (total <= 0 || active.length === 0) {
    return result;
  }

  var weightTotal = active.reduce(function(sum, item) {
    return sum + item.weight;
  }, 0);
  var assigned = 0;
  var fractions = active.map(function(item) {
    var raw = (total * item.weight) / weightTotal;
    var base = Math.floor(raw);
    result[item.name] = base;
    assigned += base;
    return {
      name: item.name,
      fraction: raw - base,
      weight: item.weight,
    };
  });

  fractions.sort(function(left, right) {
    if (right.fraction !== left.fraction) {
      return right.fraction - left.fraction;
    }
    return right.weight - left.weight;
  });

  var remaining = total - assigned;
  for (var index = 0; remaining > 0; index = (index + 1) % fractions.length) {
    result[fractions[index].name] += 1;
    remaining -= 1;
  }
  return result;
}

function addSmokeScenario(scenarios, personaName, execName, iterations) {
  if (!isPersonaEnabled(personaName)) {
    return;
  }
  scenarios['smoke_' + personaName] = {
    executor: 'shared-iterations',
    exec: execName,
    vus: intEnv('SMOKE_' + personaName.toUpperCase() + '_VUS', 1),
    iterations: intEnv('SMOKE_' + personaName.toUpperCase() + '_ITERATIONS', iterations),
    maxDuration: stringEnv('SMOKE_MAX_DURATION', '1m'),
  };
}

function addRampingScenario(scenarios, personaName, execName, options) {
  if (!isPersonaEnabled(personaName) || options.target <= 0) {
    return;
  }
  scenarios[SCENARIO + '_' + personaName] = {
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

function addBurstScenario(scenarios, personaName, execName, options) {
  if (!isPersonaEnabled(personaName) || options.vus <= 0 || options.iterations <= 0) {
    return;
  }
  scenarios[SCENARIO + '_' + personaName] = {
    executor: 'shared-iterations',
    exec: execName,
    vus: options.vus,
    iterations: options.iterations,
    maxDuration: options.maxDuration,
  };
}

function buildOptions() {
  var p95Ms = intEnv('P95_MS', 800);
  var errorRateMax = Number.parseFloat(__ENV.ERROR_RATE_MAX || '0.01');
  var homeWeight = intEnv('HOME_WEIGHT_PERCENT', 30);
  var profileWeight = intEnv('PROFILE_WEIGHT_PERCENT', 10);
  var preRideWeight = intEnv('PRERIDE_WEIGHT_PERCENT', 25);
  var inRideWeight = intEnv('INRIDE_WEIGHT_PERCENT', 20);
  var writeWeight = intEnv('WRITE_WEIGHT_PERCENT', 10);
  var healthWeight = intEnv('HEALTH_WEIGHT_PERCENT', 5);

  var thresholds = {
    http_req_failed: ['rate<' + errorRateMax],
    http_req_duration: ['p(95)<' + p95Ms],
    'http_req_duration{flow:health}': ['p(95)<300'],
    'http_req_duration{flow:course-read}': ['p(95)<' + p95Ms],
    'http_req_duration{flow:route-read}': ['p(95)<' + intEnv('ROUTE_READ_P95_MS', 1000)],
    'http_req_duration{flow:weather-read}': ['p(95)<' + intEnv('WEATHER_P95_MS', 1200)],
    'http_req_duration{flow:ride-policy}': ['p(95)<' + intEnv('RIDE_POLICY_P95_MS', 1200)],
    checks: ['rate>0.99'],
  };
  if (canRunAuthenticatedPersona()) {
    thresholds['http_req_duration{flow:profile-read}'] = ['p(95)<' + intEnv('PROFILE_P95_MS', 700)];
    thresholds['http_req_duration{flow:location-read}'] = ['p(95)<' + intEnv('LOCATION_P95_MS', 500)];
    thresholds['http_req_duration{flow:write-core}'] = ['p(95)<' + intEnv('WRITE_P95_MS', 1500)];
  }

  if (SCENARIO === 'burst') {
    var burstScenarios = {};
    addBurstScenario(burstScenarios, 'write', 'personaRideRecord', {
      vus: intEnv('BURST_WRITE_VUS', 20),
      iterations: intEnv('BURST_WRITE_ITERATIONS', 40),
      maxDuration: stringEnv('BURST_MAX_DURATION', '5m'),
    });
    addBurstScenario(burstScenarios, 'inRide', 'personaInRide', {
      vus: intEnv('BURST_INRIDE_VUS', 20),
      iterations: intEnv('BURST_INRIDE_ITERATIONS', 80),
      maxDuration: stringEnv('BURST_MAX_DURATION', '5m'),
    });
    addBurstScenario(burstScenarios, 'preRide', 'personaPreRide', {
      vus: intEnv('BURST_PRERIDE_VUS', 10),
      iterations: intEnv('BURST_PRERIDE_ITERATIONS', 20),
      maxDuration: stringEnv('BURST_MAX_DURATION', '5m'),
    });
    return {
      scenarios: burstScenarios,
      tags: {
        testid: TEST_ID,
        scenario_profile: SCENARIO,
      },
      thresholds: thresholds,
    };
  }

  if (SCENARIO === 'baseline' || SCENARIO === 'stress') {
    var totalVus = intEnv(
      SCENARIO === 'baseline' ? 'BASELINE_TOTAL_VUS' : 'STRESS_TOTAL_VUS',
      SCENARIO === 'baseline' ? 10 : 25,
    );
    var allocatedVus = allocateWeightedVus(totalVus, {
      home: homeWeight,
      profile: profileWeight,
      preRide: preRideWeight,
      inRide: inRideWeight,
      write: writeWeight,
      health: healthWeight,
    });

    var scenarios = {};
    addRampingScenario(scenarios, 'home', 'personaHomeDiscovery', {
      target: allocatedVus.home,
      rampUp: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_UP' : 'STRESS_RAMP_UP', SCENARIO === 'baseline' ? '2m' : '1m'),
      hold: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_HOLD' : 'STRESS_HOLD', SCENARIO === 'baseline' ? '5m' : '3m'),
      rampDown: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_DOWN' : 'STRESS_RAMP_DOWN', SCENARIO === 'baseline' ? '2m' : '1m'),
    });
    addRampingScenario(scenarios, 'profile', 'personaProfileSummary', {
      target: allocatedVus.profile,
      rampUp: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_UP' : 'STRESS_RAMP_UP', SCENARIO === 'baseline' ? '2m' : '1m'),
      hold: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_HOLD' : 'STRESS_HOLD', SCENARIO === 'baseline' ? '5m' : '3m'),
      rampDown: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_DOWN' : 'STRESS_RAMP_DOWN', SCENARIO === 'baseline' ? '2m' : '1m'),
    });
    addRampingScenario(scenarios, 'preRide', 'personaPreRide', {
      target: allocatedVus.preRide,
      rampUp: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_UP' : 'STRESS_RAMP_UP', SCENARIO === 'baseline' ? '2m' : '1m'),
      hold: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_HOLD' : 'STRESS_HOLD', SCENARIO === 'baseline' ? '5m' : '3m'),
      rampDown: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_DOWN' : 'STRESS_RAMP_DOWN', SCENARIO === 'baseline' ? '2m' : '1m'),
    });
    addRampingScenario(scenarios, 'inRide', 'personaInRide', {
      target: allocatedVus.inRide,
      rampUp: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_UP' : 'STRESS_RAMP_UP', SCENARIO === 'baseline' ? '2m' : '1m'),
      hold: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_HOLD' : 'STRESS_HOLD', SCENARIO === 'baseline' ? '5m' : '3m'),
      rampDown: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_DOWN' : 'STRESS_RAMP_DOWN', SCENARIO === 'baseline' ? '2m' : '1m'),
    });
    addRampingScenario(scenarios, 'write', 'personaRideRecord', {
      target: allocatedVus.write,
      rampUp: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_UP' : 'STRESS_RAMP_UP', SCENARIO === 'baseline' ? '2m' : '1m'),
      hold: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_HOLD' : 'STRESS_HOLD', SCENARIO === 'baseline' ? '5m' : '3m'),
      rampDown: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_DOWN' : 'STRESS_RAMP_DOWN', SCENARIO === 'baseline' ? '2m' : '1m'),
    });
    addRampingScenario(scenarios, 'health', 'personaHealth', {
      target: allocatedVus.health,
      rampUp: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_UP' : 'STRESS_RAMP_UP', SCENARIO === 'baseline' ? '2m' : '1m'),
      hold: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_HOLD' : 'STRESS_HOLD', SCENARIO === 'baseline' ? '5m' : '3m'),
      rampDown: stringEnv(SCENARIO === 'baseline' ? 'BASELINE_RAMP_DOWN' : 'STRESS_RAMP_DOWN', SCENARIO === 'baseline' ? '2m' : '1m'),
    });

    return {
      scenarios: scenarios,
      tags: {
        testid: TEST_ID,
        scenario_profile: SCENARIO,
      },
      thresholds: thresholds,
    };
  }
  var scenarios = {};
  addSmokeScenario(scenarios, 'home', 'personaHomeDiscovery', 2);
  addSmokeScenario(scenarios, 'profile', 'personaProfileSummary', 2);
  addSmokeScenario(scenarios, 'preRide', 'personaPreRide', 2);
  addSmokeScenario(scenarios, 'inRide', 'personaInRide', 2);
  addSmokeScenario(scenarios, 'write', 'personaRideRecord', 1);
  addSmokeScenario(scenarios, 'health', 'personaHealth', 2);

  return {
    scenarios: scenarios,
    tags: {
      testid: TEST_ID,
      scenario_profile: SCENARIO,
    },
    thresholds: thresholds,
  };
}

export var options = buildOptions();

function authHeaders(authToken) {
  var token = authToken || __ENV.AUTH_BEARER_TOKEN;
  return token ? { Authorization: 'Bearer ' + token } : {};
}

function getJson(path, tags, params) {
  tags = tags || {};
  params = params || {};
  var auth = authHeaders(params.authToken);
  var paramHeaders = params.headers || {};
  var requestId = generateCorrelationId();
  var traceId = generateCorrelationId();
  var mergedHeaders = { Accept: 'application/json' };
  Object.keys(auth).forEach(function(key) { mergedHeaders[key] = auth[key]; });
  Object.keys(paramHeaders).forEach(function(key) { mergedHeaders[key] = paramHeaders[key]; });
  var merged = {
    tags: baseRequestTags(tags),
    headers: withCorrelationHeaders(mergedHeaders, requestId, traceId),
  };
  if (params.expectedStatuses) {
    merged.responseCallback = http.expectedStatuses.apply(null, params.expectedStatuses);
  }
  var response = http.get(BASE_URL + path, merged);
  logSlowRequest(response, 'GET', path, tags.endpoint, requestId, traceId);
  return response;
}

function postJson(path, body, tags, authToken) {
  tags = tags || {};
  var auth = authHeaders(authToken);
  var requestId = generateCorrelationId();
  var traceId = generateCorrelationId();
  var mergedHeaders = { 'Content-Type': 'application/json', Accept: 'application/json' };
  Object.keys(auth).forEach(function(key) { mergedHeaders[key] = auth[key]; });
  var response = http.post(BASE_URL + path, JSON.stringify(body), {
    tags: baseRequestTags(tags),
    headers: withCorrelationHeaders(mergedHeaders, requestId, traceId),
  });
  logSlowRequest(response, 'POST', path, tags.endpoint, requestId, traceId);
  return response;
}

function commonChecks(response, expectedStatus) {
  var checksObj = {};
  checksObj['status is ' + expectedStatus] = function(r) { return r.status === expectedStatus; };
  checksObj['body is not empty'] = function(r) { return !!r.body; };
  return check(response, checksObj);
}

function parseJsonBody(response) {
  if (!response || !response.body) {
    return null;
  }
  try {
    return JSON.parse(response.body);
  } catch (_) {
    return null;
  }
}

function runHealthCheck() {
  group('health', function() {
    var response = getJson('/health', { flow: 'health', endpoint: 'health' });
    commonChecks(response, 200);
  });
}

function extractCourseId(response) {
  if (!response || response.status !== 200) {
    return null;
  }
  try {
    var payload = JSON.parse(response.body);
    var data = payload && payload.data;
    if (data && Array.isArray(data.courses) && data.courses.length > 0) {
      var first = data.courses[0];
      return (first && first.courseId) || (first && first.id) || null;
    }
    if (data && Array.isArray(data.items) && data.items.length > 0) {
      var firstItem = data.items[0];
      return (firstItem && firstItem.courseId) || (firstItem && firstItem.id) || null;
    }
    if (Array.isArray(data) && data.length > 0) {
      var firstData = data[0];
      return (firstData && firstData.courseId) || (firstData && firstData.id) || null;
    }
    return null;
  } catch (_) {
    return null;
  }
}

function extractAccessToken(response) {
  if (!response || response.status !== 200) {
    return null;
  }
  try {
    var payload = JSON.parse(response.body);
    return payload && payload.data && payload.data.accessToken ? payload.data.accessToken : null;
  } catch (_) {
    return null;
  }
}

function extractRideRecordId(response) {
  if (!response || (response.status !== 200 && response.status !== 201)) {
    return null;
  }
  try {
    var payload = JSON.parse(response.body);
    var data = payload && payload.data;
    return data && (data.rideRecordId || data.id) ? String(data.rideRecordId || data.id) : null;
  } catch (_) {
    return null;
  }
}

function postSetupJson(path, body, endpoint) {
  var requestId = generateCorrelationId();
  var traceId = generateCorrelationId();
  return http.post(BASE_URL + path, JSON.stringify(body), {
    tags: baseRequestTags({ flow: 'setup', endpoint: endpoint }),
    headers: withCorrelationHeaders({ 'Content-Type': 'application/json', Accept: 'application/json' }, requestId, traceId),
    responseCallback: http.expectedStatuses(200, 400, 401, 409),
  });
}

function resolveSetupAuthToken() {
  if (__ENV.AUTH_BEARER_TOKEN) {
    return __ENV.AUTH_BEARER_TOKEN;
  }
  if (!canRunAuthenticatedPersona()) {
    return null;
  }

  var safeTestId = TEST_ID.replace(/[^a-zA-Z0-9._-]/g, '-');
  var email = stringEnv('AUTH_EMAIL', 'k6-' + safeTestId + '@example.com');
  var password = stringEnv('AUTH_PASSWORD', 'Loadtest123!');
  var displayName = stringEnv('AUTH_DISPLAY_NAME', 'k6-loadtest');
  var registerBody = {
    email: email,
    password: password,
    displayName: displayName,
    profileImageUrl: null,
    legacyExternalId: null,
  };
  var loginBody = { email: email, password: password };

  var loginResponse = postSetupJson('/api/v1/auth/login', loginBody, 'auth-login-setup');
  var loggedInToken = extractAccessToken(loginResponse);
  if (loggedInToken) {
    return loggedInToken;
  }

  var registerResponse = postSetupJson('/api/v1/auth/register', registerBody, 'auth-register-setup');
  return extractAccessToken(registerResponse);
}

export function setup() {
  var authToken = resolveSetupAuthToken();
  var explicitCourseId = __ENV.COURSE_ID;
  if (explicitCourseId) {
    return { courseId: explicitCourseId, authToken: authToken };
  }

  var auth = authHeaders(authToken);
  var headers = { Accept: 'application/json' };
  Object.keys(auth).forEach(function(key) { headers[key] = auth[key]; });

  var featured = http.get(BASE_URL + '/api/v1/courses/featured', {
    tags: baseRequestTags({ flow: 'setup', endpoint: 'courses-featured-setup' }),
    headers: headers,
  });
  var featuredCourseId = extractCourseId(featured);
  if (featuredCourseId) {
    return { courseId: String(featuredCourseId), authToken: authToken };
  }

  var courses = http.get(BASE_URL + '/api/v1/courses?limit=1', {
    tags: baseRequestTags({ flow: 'setup', endpoint: 'courses-list-setup' }),
    headers: headers,
  });
  var listCourseId = extractCourseId(courses);
  return { courseId: listCourseId ? String(listCourseId) : null, authToken: authToken };
}

function resolvedCourseId(setupData) {
  return __ENV.COURSE_ID || (setupData && setupData.courseId) || null;
}

function resolvedAuthToken(setupData) {
  return __ENV.AUTH_BEARER_TOKEN || (setupData && setupData.authToken) || null;
}

function runCourseReads(setupData) {
  group('course reads', function() {
    var featured = getJson('/api/v1/courses/featured', {
      flow: 'course-read',
      endpoint: 'courses-featured',
    });
    commonChecks(featured, 200);

    var courses = getJson('/api/v1/courses?limit=10', {
      flow: 'course-read',
      endpoint: 'courses-list',
    });
    commonChecks(courses, 200);

    var courseId = resolvedCourseId(setupData);
    if (!courseId) {
      return;
    }

    var detail = getJson('/api/v1/courses/' + courseId, {
      flow: 'course-read',
      endpoint: 'course-detail',
    });
    commonChecks(detail, 200);
  });
}

function runRoutePointsRead(setupData) {
  var courseId = resolvedCourseId(setupData);
  if (!courseId) {
    return;
  }

  group('route points read', function() {
    var routePoints = getJson('/api/v1/courses/' + courseId + '/route-points', {
      flow: 'route-read',
      endpoint: 'course-route-points',
    });
    commonChecks(routePoints, 200);
  });
}

function runWeatherRead() {
  if (!ENABLE_WEATHER_READ) {
    return;
  }
  group('weather current', function() {
    var lat = stringEnv('WEATHER_LAT', '37.5665');
    var lon = stringEnv('WEATHER_LON', '126.9780');
    var response = getJson('/api/v1/weather/current?lat=' + lat + '&lon=' + lon, {
      flow: 'weather-read',
      endpoint: 'weather-current',
    });
    commonChecks(response, 200);
    check(response, {
      'weather freshness status is valid': function(r) {
        var payload = parseJsonBody(r);
        var status = payload && payload.data && payload.data.freshnessStatus;
        return [
          'FRESH_PROVIDER',
          'FRESH_CACHE',
          'STALE_LAST_SUCCESS',
          'UNAVAILABLE',
        ].indexOf(status) >= 0;
      },
      'weather unavailable keeps payload explicit': function(r) {
        var payload = parseJsonBody(r);
        var data = payload && payload.data;
        if (!data || data.freshnessStatus !== 'UNAVAILABLE') {
          return true;
        }
        return data.weather === null
          && data.wind === null
          && data.stale === false
          && data.staleReason === 'PROVIDER_FAILURE';
      },
    });
  });
}

function runRidePolicy(setupData) {
  var courseId = resolvedCourseId(setupData);
  if (!courseId) {
    return;
  }

  group('ride policy evaluate', function() {
    var response = postJson(
      '/api/v1/courses/' + courseId + '/ride-policy/evaluate',
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

function runRecentLocation(setupData) {
  var authToken = resolvedAuthToken(setupData);
  if (!authToken) {
    return;
  }

  group('recent location', function() {
    var response = getJson('/api/v1/location/me/recent', {
      flow: 'location-read',
      endpoint: 'location-me-recent',
    }, { authToken: authToken, expectedStatuses: [200, 404] });

    check(response, {
      'status is 200 or 404': function(r) { return r.status === 200 || r.status === 404; },
    });
  });
}

function runProfileSummary(setupData) {
  var authToken = resolvedAuthToken(setupData);
  if (!authToken) {
    return;
  }

  group('profile activity summary', function() {
    var response = getJson('/api/v1/profile/me/activity-summary', {
      flow: 'profile-read',
      endpoint: 'profile-activity-summary',
    }, { authToken: authToken });
    commonChecks(response, 200);
  });
}

function runRideRecordWrite(setupData) {
  var authToken = resolvedAuthToken(setupData);
  if (!authToken) {
    return;
  }

  var endedAtMillis = Date.now();
  var startedAtMillis = endedAtMillis - 5 * 60 * 1000;
  var payload = {
    clientRideId: buildClientRideId(),
    startedAt: new Date(startedAtMillis).toISOString(),
    endedAt: new Date(endedAtMillis).toISOString(),
    visibility: 'PRIVATE',
    summary: {
      distanceM: intEnv('WRITE_DISTANCE_M', 1200),
      durationSec: Math.max(10, Math.round((endedAtMillis - startedAtMillis) / 1000)),
    },
    routePoints: buildRideRecordPoints(startedAtMillis, endedAtMillis),
  };

  group('ride record save', function() {
    var response = postJson('/api/v1/ride-records', payload, {
      flow: 'write-core',
      endpoint: 'ride-record-save',
    }, authToken);

    check(response, {
      'status is 200 or 201': function(r) { return r.status === 200 || r.status === 201; },
      'body is not empty': function(r) { return !!r.body; },
    });

    var rideRecordId = extractRideRecordId(response);
    if (rideRecordId && WRITE_POLL_FINALIZATION) {
      pollRideRecordFinalization(rideRecordId, authToken);
    }
  });
}

function buildClientRideId() {
  var iteration = typeof __ITER === 'undefined' ? 0 : __ITER;
  return [
    TEST_ID.replace(/[^a-zA-Z0-9._:-]/g, '-'),
    'ride',
    'vu' + (__VU || 0),
    'iter' + iteration,
    Math.random().toString(16).slice(2, 10),
  ].join('-').slice(0, 80);
}

function buildRideRecordPoints(startedAtMillis, endedAtMillis) {
  var startLat = Number.parseFloat(stringEnv('WRITE_START_LAT', '37.5665'));
  var startLon = Number.parseFloat(stringEnv('WRITE_START_LON', '126.9780'));
  var endLat = Number.parseFloat(stringEnv('WRITE_END_LAT', '37.5670'));
  var endLon = Number.parseFloat(stringEnv('WRITE_END_LON', '126.9785'));
  var points = [];
  for (var i = 0; i < WRITE_ROUTE_POINT_COUNT; i += 1) {
    var ratio = WRITE_ROUTE_POINT_COUNT === 1 ? 0 : i / (WRITE_ROUTE_POINT_COUNT - 1);
    points.push({
      pointOrder: i + 1,
      latitude: startLat + ((endLat - startLat) * ratio),
      longitude: startLon + ((endLon - startLon) * ratio),
      capturedAt: new Date(startedAtMillis + ((endedAtMillis - startedAtMillis) * ratio)).toISOString(),
    });
  }
  return points;
}

function pollRideRecordFinalization(rideRecordId, authToken) {
  group('ride record finalization poll', function() {
    for (var attempt = 0; attempt < WRITE_FINALIZATION_POLL_ATTEMPTS; attempt += 1) {
      var response = getJson('/api/v1/ride-records/' + rideRecordId, {
        flow: 'write-finalization',
        endpoint: 'ride-record-finalization-status',
      }, { authToken: authToken });

      check(response, {
        'status is 200': function(r) { return r.status === 200; },
        'finalization status exists': function(r) {
          try {
            var payload = JSON.parse(r.body);
            return !!(payload && payload.data && payload.data.status);
          } catch (_) {
            return false;
          }
        },
      });
      if (response.body && response.body.indexOf('"READY"') >= 0) {
        return;
      }
      sleep(WRITE_FINALIZATION_POLL_INTERVAL_SECONDS);
    }
  });
}

export function coreJourney(setupData) {
  runHealthCheck();
  runCourseReads(setupData);
  runRoutePointsRead(setupData);
  runWeatherRead();
  runRidePolicy(setupData);
  runRecentLocation(setupData);
  runRideRecordWrite(setupData);
  sleep(Number.parseFloat(stringEnv('SLEEP_SECONDS', '1')));
}

export function personaHomeDiscovery(setupData) {
  runCourseReads(setupData);
  sleep(Number.parseFloat(stringEnv('SLEEP_SECONDS', '1')));
}

export function personaProfileSummary(setupData) {
  runProfileSummary(setupData);
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
  runRecentLocation(setupData);
  sleep(Number.parseFloat(stringEnv('SLEEP_SECONDS', '1')));
}

export function personaRideRecord(setupData) {
  runRideRecordWrite(setupData);
  sleep(Number.parseFloat(stringEnv('SLEEP_SECONDS', '1')));
}

export function personaHealth() {
  runHealthCheck();
  sleep(Number.parseFloat(stringEnv('SLEEP_SECONDS', '1')));
}

export function handleSummary(data) {
  var result = {};
  data.bike_metadata = {
    test_id: TEST_ID,
    scenario: SCENARIO,
    personas: ACTIVE_PERSONAS,
    weather_enabled: ENABLE_WEATHER_READ,
    write_route_point_count: WRITE_ROUTE_POINT_COUNT,
    write_poll_finalization: WRITE_POLL_FINALIZATION,
  };
  var sanitizedData = sanitizedSummaryData(data);
  result.stdout = textSummary(data, { indent: ' ', enableColors: true });
  result[SUMMARY_DIR + '/' + TEST_ID + '-summary.json'] = JSON.stringify(sanitizedData, null, 2);
  return result;
}

function sanitizedSummaryData(data) {
  var sanitized = JSON.parse(JSON.stringify(data));
  if (sanitized.setup_data && sanitized.setup_data.tokens) {
    sanitized.setup_data.tokens = '[REDACTED]';
  }
  if (sanitized.setup_data && sanitized.setup_data.authToken) {
    sanitized.setup_data.authToken = '[REDACTED]';
  }
  return sanitized;
}

function textSummary(data, options) {
  options = options || {};
  var indent = options.indent || '';
  var enableColors = options.enableColors || false;
  var color = function(value, _name) { return value; };
  var lines = [];
  var iterations = 0;
  if (data.metrics.iterations && data.metrics.iterations.values) {
    iterations = data.metrics.iterations.values.count || 0;
  }
  var failedRate = 0;
  if (data.metrics.http_req_failed && data.metrics.http_req_failed.values) {
    failedRate = data.metrics.http_req_failed.values.rate || 0;
  }
  var p95 = 0;
  if (data.metrics.http_req_duration && data.metrics.http_req_duration.values) {
    p95 = data.metrics.http_req_duration.values['p(95)'] || 0;
  }
  var checksRate = 0;
  if (data.metrics.checks && data.metrics.checks.values) {
    checksRate = data.metrics.checks.values.rate || 0;
  }
  lines.push(indent + 'scenario_profile: ' + SCENARIO);
  lines.push(indent + 'testid: ' + TEST_ID);
  lines.push(indent + 'slow_request_sample_ms: ' + SLOW_REQUEST_SAMPLE_MS);
  lines.push(indent + 'slow_request_samples: stdout JSON lines type=slow_request_sample');
  lines.push(indent + 'iterations: ' + iterations);
  lines.push(indent + 'http_req_failed(rate): ' + failedRate);
  lines.push(indent + 'http_req_duration(p95): ' + p95 + ' ms');
  lines.push(indent + 'checks(rate): ' + checksRate);
  return enableColors ? color(lines.join('\n'), 'cyan') : lines.join('\n');
}

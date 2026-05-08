import http from 'k6/http';
import { check, group, sleep } from 'k6';

var BASE_URL = (__ENV.BASE_URL || '').replace(/\/$/, '');
var SCENARIO = __ENV.SCENARIO || 'smoke';
var ACTIVE_PERSONAS = parsePersonaFilter(__ENV.PERSONAS || 'home,preRide,inRide,write,health');
var TEST_ID = __ENV.TEST_ID || 'bike-' + SCENARIO;
var SUMMARY_DIR = (__ENV.SUMMARY_DIR || 'ops/loadtest/results').replace(/\/$/, '');
var ENABLE_WEATHER_READ = ((__ENV.ENABLE_WEATHER_READ || 'true') + '').toLowerCase() !== 'false';

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
  return ACTIVE_PERSONAS.indexOf(name) !== -1;
}

function baseRequestTags(extra) {
  extra = extra || {};
  var result = { testid: TEST_ID, scenario_profile: SCENARIO };
  Object.keys(extra).forEach(function(key) {
    result[key] = extra[key];
  });
  return result;
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

function buildOptions() {
  var p95Ms = intEnv('P95_MS', 800);
  var errorRateMax = Number.parseFloat(__ENV.ERROR_RATE_MAX || '0.01');
  var homeWeight = intEnv('HOME_WEIGHT_PERCENT', 35);
  var preRideWeight = intEnv('PRERIDE_WEIGHT_PERCENT', 30);
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
    'http_req_duration{flow:location-read}': ['p(95)<' + intEnv('LOCATION_P95_MS', 500)],
    'http_req_duration{flow:write-core}': ['p(95)<' + intEnv('WRITE_P95_MS', 1500)],
    checks: ['rate>0.99'],
  };

  if (SCENARIO === 'baseline' || SCENARIO === 'stress') {
    var totalVus = intEnv(
      SCENARIO === 'baseline' ? 'BASELINE_TOTAL_VUS' : 'STRESS_TOTAL_VUS',
      SCENARIO === 'baseline' ? 10 : 25,
    );

    var scenarios = {};
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

function authHeaders() {
  var token = __ENV.AUTH_BEARER_TOKEN;
  return token ? { Authorization: 'Bearer ' + token } : {};
}

function getJson(path, tags, params) {
  tags = tags || {};
  params = params || {};
  var auth = authHeaders();
  var paramHeaders = params.headers || {};
  var mergedHeaders = { Accept: 'application/json' };
  Object.keys(auth).forEach(function(key) { mergedHeaders[key] = auth[key]; });
  Object.keys(paramHeaders).forEach(function(key) { mergedHeaders[key] = paramHeaders[key]; });
  var merged = {
    tags: baseRequestTags(tags),
    headers: mergedHeaders,
  };
  return http.get(BASE_URL + path, merged);
}

function postJson(path, body, tags) {
  tags = tags || {};
  var auth = authHeaders();
  var mergedHeaders = { 'Content-Type': 'application/json', Accept: 'application/json' };
  Object.keys(auth).forEach(function(key) { mergedHeaders[key] = auth[key]; });
  return http.post(BASE_URL + path, JSON.stringify(body), {
    tags: baseRequestTags(tags),
    headers: mergedHeaders,
  });
}

function commonChecks(response, expectedStatus) {
  var checksObj = {};
  checksObj['status is ' + expectedStatus] = function(r) { return r.status === expectedStatus; };
  checksObj['body is not empty'] = function(r) { return !!r.body; };
  return check(response, checksObj);
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

export function setup() {
  var explicitCourseId = __ENV.COURSE_ID;
  if (explicitCourseId) {
    return { courseId: explicitCourseId };
  }

  var auth = authHeaders();
  var headers = { Accept: 'application/json' };
  Object.keys(auth).forEach(function(key) { headers[key] = auth[key]; });

  var featured = http.get(BASE_URL + '/api/v1/courses/featured', {
    tags: baseRequestTags({ flow: 'setup', endpoint: 'courses-featured-setup' }),
    headers: headers,
  });
  var featuredCourseId = extractCourseId(featured);
  if (featuredCourseId) {
    return { courseId: String(featuredCourseId) };
  }

  var courses = http.get(BASE_URL + '/api/v1/courses?limit=1', {
    tags: baseRequestTags({ flow: 'setup', endpoint: 'courses-list-setup' }),
    headers: headers,
  });
  var listCourseId = extractCourseId(courses);
  return { courseId: listCourseId ? String(listCourseId) : null };
}

function resolvedCourseId(setupData) {
  return __ENV.COURSE_ID || (setupData && setupData.courseId) || null;
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

function runRecentLocation() {
  if (!__ENV.AUTH_BEARER_TOKEN) {
    return;
  }

  group('recent location', function() {
    var response = getJson('/api/v1/location/me/recent', {
      flow: 'location-read',
      endpoint: 'location-me-recent',
    });

    check(response, {
      'status is 200 or 404': function(r) { return r.status === 200 || r.status === 404; },
    });
  });
}

function runRideRecordWrite() {
  if (!__ENV.AUTH_BEARER_TOKEN) {
    return;
  }

  var payload = {
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

  group('ride record save', function() {
    var response = postJson('/api/v1/ride-records', payload, {
      flow: 'write-core',
      endpoint: 'ride-record-save',
    });

    check(response, {
      'status is 200 or 201': function(r) { return r.status === 200 || r.status === 201; },
      'body is not empty': function(r) { return !!r.body; },
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
  var result = {};
  result.stdout = textSummary(data, { indent: ' ', enableColors: true });
  result[SUMMARY_DIR + '/' + TEST_ID + '-summary.json'] = JSON.stringify(data, null, 2);
  return result;
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
  lines.push(indent + 'iterations: ' + iterations);
  lines.push(indent + 'http_req_failed(rate): ' + failedRate);
  lines.push(indent + 'http_req_duration(p95): ' + p95 + ' ms');
  lines.push(indent + 'checks(rate): ' + checksRate);
  return enableColors ? color(lines.join('\n'), 'cyan') : lines.join('\n');
}

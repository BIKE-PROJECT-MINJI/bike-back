import fs from 'node:fs';
import path from 'node:path';

const args = parseArgs(process.argv.slice(2));
const summary = readJson(requiredArg('summary'));
const metricsText = readText(args.metrics || '');
const stdoutText = readText(args.stdout || '');
const outputPath = args.output || defaultOutputPath(args.summary);

const report = [
  '# BIKE 백엔드 부하테스트 보고서',
  '',
  `- 테스트 ID: ${testId()}`,
  `- 실행 시간: ${durationSeconds()}초`,
  `- 결론: ${overallConclusion()}`,
  '',
  '## 1. 사람이 이해하는 테스트 상황',
  '',
  scenarioDescription(),
  '',
  '## 2. 전체 결과',
  '',
  table([
    ['항목', '결과', '해석'],
    ['요청 수', metricValue('http_reqs', 'count'), '서버가 받은 전체 HTTP 요청 수'],
    ['실패율', percent(metricValue('http_req_failed', 'rate')), '0%에 가까울수록 정상'],
    ['체크 성공률', percent(metricValue('checks', 'rate')), '시나리오가 기대한 응답을 받았는지'],
    ['전체 p95', ms(metricValue('http_req_duration', 'p(95)')), '100명 중 95명 정도가 이 시간 안에 응답받는다는 뜻'],
    ['최대 응답시간', ms(metricValue('http_req_duration', 'max')), '가장 오래 걸린 요청'],
  ]),
  '',
  '## 3. API별 체감 속도',
  '',
  apiTable(),
  '',
  '## 4. 내부 로직별 병목 후보',
  '',
  operationTable(),
  '',
  '## 5. 느린 요청 샘플',
  '',
  slowSampleSection(),
  '',
  '## 6. 운영 판단',
  '',
  operationAdvice(),
  '',
].join('\n');

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, report);
console.log(outputPath);

function parseArgs(values) {
  const parsed = {};
  for (let index = 0; index < values.length; index += 1) {
    const key = values[index];
    if (!key.startsWith('--')) {
      continue;
    }
    parsed[key.slice(2)] = values[index + 1] || '';
    index += 1;
  }
  return parsed;
}

function requiredArg(name) {
  if (!args[name]) {
    throw new Error(`missing --${name}`);
  }
  return args[name];
}

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function readText(filePath) {
  return filePath && fs.existsSync(filePath) ? fs.readFileSync(filePath, 'utf8') : '';
}

function defaultOutputPath(summaryPath) {
  return summaryPath.replace(/-summary\.json$/, '-readable-report.md');
}

function testId() {
  return summary?.bike_metadata?.test_id || path.basename(args.summary, '-summary.json');
}

function durationSeconds() {
  const millis = summary?.state?.testRunDurationMs || 0;
  return (millis / 1000).toFixed(1);
}

function metricValue(metricName, valueName) {
  const value = summary?.metrics?.[metricName]?.values?.[valueName];
  return Number.isFinite(value) ? value : 0;
}

function percent(value) {
  return `${(Number(value) * 100).toFixed(2)}%`;
}

function ms(value) {
  return `${Number(value).toFixed(1)}ms`;
}

function overallConclusion() {
  const failedRate = metricValue('http_req_failed', 'rate');
  const checksRate = metricValue('checks', 'rate');
  if (failedRate > 0.01) {
    return '실패율이 높아 병목 분석보다 장애 원인 확인이 먼저입니다.';
  }
  if (checksRate < 0.99) {
    return '일부 사용자 행동이 기대대로 끝나지 않았습니다.';
  }
  return '이번 실행 기준으로 큰 실패는 없고, p95와 내부 operation을 기준으로 병목 후보를 볼 수 있습니다.';
}

function scenarioDescription() {
  const profile = summary?.bike_metadata?.scenario
    || ['smoke', 'baseline', 'stress', 'burst'].find((scenario) => path.basename(args.summary).includes(scenario))
    || 'unknown';
  const descriptions = {
    smoke: '짧은 예행연습입니다. API 경로, 인증, 기본 응답이 깨지지 않았는지 확인합니다.',
    baseline: '일반적인 사용량을 가정합니다. 홈 탐색, 코스 보기, 주행 전 확인, 주행 중 HUD 요청이 섞입니다.',
    stress: '평소보다 높은 사용량을 가정합니다. 어느 지점부터 응답이 느려지거나 실패가 생기는지 봅니다.',
    burst: '여러 사용자가 거의 동시에 주행 기록을 저장하거나 주행 중 위치 판정을 요청하는 상황입니다. DB 쓰기, 후처리 큐, 정합성 문제가 드러나기 쉽습니다.',
  };
  return descriptions[profile] || `시나리오 프로필은 ${profile}입니다.`;
}

function apiTable() {
  const rows = [['API 행동', '평균', 'p95', '최대', '해석']];
  Object.entries(summary.metrics || {})
    .filter(([name]) => name.startsWith('http_req_duration{flow:'))
    .sort(([left], [right]) => left.localeCompare(right))
    .forEach(([name, metric]) => {
      const flow = name.match(/flow:([^}]+)/)?.[1] || name;
      const values = metric.values || {};
      rows.push([flow, ms(values.avg || 0), ms(values['p(95)'] || 0), ms(values.max || 0), apiMeaning(flow)]);
    });
  return rows.length > 1 ? table(rows) : 'API별 flow metric이 없습니다.';
}

function apiMeaning(flow) {
  const meanings = {
    health: '서버 생존 확인',
    'course-read': '홈/코스 목록/코스 상세 보기',
    'route-read': '지도 경로 포인트 읽기',
    'ride-policy': '코스 이탈/완주/HUD 판정',
    'write-core': '주행 기록 저장',
    'write-finalization': '저장된 주행 기록 후처리 상태 확인',
    'profile-read': '내 활동 요약 조회',
    'location-read': '최근 위치 조회',
    'weather-read': '날씨 조회',
  };
  return meanings[flow] || '사용자 행동 묶음';
}

function operationTable() {
  if (!metricsText) {
    return 'Prometheus scrape 파일이 없어 내부 operation 표는 생성하지 않았습니다.';
  }
  const operations = parseOperationMetrics(metricsText);
  if (operations.length === 0) {
    return 'Prometheus scrape에서 `bike_operation_duration` metric을 찾지 못했습니다.';
  }
  const rows = [['내부 로직', '호출 수', '평균', 'p95 근사', '판단']];
  operations.slice(0, 15).forEach((operation) => {
    rows.push([
      operation.name,
      String(operation.count),
      operation.avgSeconds === null ? '계산 불가' : `${(operation.avgSeconds * 1000).toFixed(1)}ms`,
      operation.p95Seconds === null ? '계산 불가' : `${(operation.p95Seconds * 1000).toFixed(1)}ms`,
      operation.p95Seconds !== null && operation.p95Seconds > 0.2 ? '우선 확인 후보' : '현재는 낮음',
    ]);
  });
  return table(rows);
}

function parseOperationMetrics(text) {
  const sums = new Map();
  const counts = new Map();
  const buckets = new Map();
  text.split(/\r?\n/).forEach((line) => {
    const parsed = parseMetricLine(line);
    if (!parsed || parsed.labels.outcome !== 'success') {
      return;
    }
    const operation = parsed.labels.operation;
    if (!operation) {
      return;
    }
    if (parsed.name === 'bike_operation_duration_seconds_sum') {
      sums.set(operation, parsed.value);
    }
    if (parsed.name === 'bike_operation_duration_seconds_count') {
      counts.set(operation, parsed.value);
    }
    if (parsed.name === 'bike_operation_duration_seconds_bucket') {
      const bucket = buckets.get(operation) || [];
      bucket.push({ le: parsed.labels.le, value: parsed.value });
      buckets.set(operation, bucket);
    }
  });
  return [...counts.entries()]
    .map(([name, count]) => ({
      name,
      count,
      avgSeconds: sums.has(name) && count > 0 ? sums.get(name) / count : null,
      p95Seconds: approximateQuantile(buckets.get(name) || [], count, 0.95),
    }))
    .sort((left, right) => (right.p95Seconds || right.avgSeconds) - (left.p95Seconds || left.avgSeconds));
}

function parseMetricLine(line) {
  const match = line.match(/^([a-zA-Z_:][a-zA-Z0-9_:]*)\{([^}]*)}\s+([0-9.eE+-]+)$/);
  if (!match) {
    return null;
  }
  return { name: match[1], labels: parseLabels(match[2]), value: Number(match[3]) };
}

function parseLabels(raw) {
  const labels = {};
  raw.split(',').forEach((pair) => {
    const match = pair.match(/^([^=]+)="(.*)"$/);
    if (match) {
      labels[match[1]] = match[2];
    }
  });
  return labels;
}

function approximateQuantile(bucket, count, quantile) {
  if (bucket.length === 0 || count === 0) {
    return null;
  }
  const target = count * quantile;
  const sorted = bucket
    .filter((item) => item.le !== '+Inf')
    .map((item) => ({ le: Number(item.le), value: item.value }))
    .sort((left, right) => left.le - right.le);
  const found = sorted.find((item) => item.value >= target);
  return found ? found.le : null;
}

function slowSampleSection() {
  const samples = stdoutText
    .split(/\r?\n/)
    .filter((line) => line.includes('slow_request_sample'))
    .slice(0, 10);
  if (samples.length === 0) {
    return '느린 요청 샘플은 없습니다. 기준치 이상 느린 요청이 없었거나 stdout 파일이 제공되지 않았습니다.';
  }
  return samples.map((sample) => `- ${sample}`).join('\n');
}

function operationAdvice() {
  return [
    '- 실패율이 1%를 넘으면 성능 튜닝보다 장애 원인 확인이 먼저입니다.',
    '- API p95가 높고 내부 operation p95도 같이 높으면 해당 서비스 로직이 병목 후보입니다.',
    '- API p95는 높은데 내부 operation이 낮으면 네트워크, 인증 필터, JSON 직렬화, DB 커넥션 대기, 외부 시스템을 봅니다.',
    '- write-finalization이 밀리면 주행 기록 저장 자체보다 후처리 worker, DB lock, job pending 증가를 먼저 확인합니다.',
  ].join('\n');
}

function table(rows) {
  const header = `| ${rows[0].join(' | ')} |`;
  const divider = `| ${rows[0].map(() => '---').join(' | ')} |`;
  const body = rows.slice(1).map((row) => `| ${row.join(' | ')} |`);
  return [header, divider, ...body].join('\n');
}

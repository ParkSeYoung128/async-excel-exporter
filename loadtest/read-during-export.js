/*
 * 엑셀 생성 중 조회 API 응답 시간 측정
 *
 * "생성 중에도 API 는 즉시 응답한다"를 과제 리소스 제약(앱 0.5 CPU / 1G) 아래에서 확인한다.
 * 같은 비율의 목록 조회를 두 구간에서 보내고 응답 시간 분포를 비교한다.
 *
 *   0 ~ 30초   idle       생성 없이 목록 조회만 (초당 10건 = 화면 20개가 2초마다 폴링)
 *   30초       trigger    엑셀 생성 10건 요청 → 워커가 한 건씩 연속 처리 (10만 건 기준 약 20초 이상)
 *   31 ~ 61초  exporting  같은 비율로 목록 조회
 *
 * 실행 (앱이 UP 인 상태에서):
 *   docker compose --profile loadtest run --rm k6 run /scripts/read-during-export.js
 *
 * 요청 폭주 시 202 응답·큐 초과 작업의 최종 처리는 통합 테스트(ExportJobDispatchRejectionTest)로 검증하므로 여기서 다루지 않는다.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://app:8080';
const LIST_URL = `${BASE_URL}/api/export-jobs?page=0&size=20`;
const READS_PER_SECOND = 10;
const PHASE_DURATION = '30s';
const EXPORT_COUNT = 10;
const WAIT_FOR_EXPORTS_SECONDS = 300;

export const options = {
  scenarios: {
    idle: {
      executor: 'constant-arrival-rate',
      exec: 'readList',
      rate: READS_PER_SECOND,
      timeUnit: '1s',
      duration: PHASE_DURATION,
      preAllocatedVUs: 20,
    },
    trigger: {
      executor: 'shared-iterations',
      exec: 'triggerExports',
      vus: 1,
      iterations: 1,
      startTime: '30s',
    },
    exporting: {
      executor: 'constant-arrival-rate',
      exec: 'readList',
      rate: READS_PER_SECOND,
      timeUnit: '1s',
      duration: PHASE_DURATION,
      startTime: '31s',
      preAllocatedVUs: 20,
    },
  },
  // 구간(시나리오)별 값을 요약에 따로 집계하기 위한 임계값. 합격/불합격 판정이 목적이 아니라 측정이 목적이다.
  thresholds: {
    'http_req_duration{scenario:idle}': ['max>=0'],
    'http_req_duration{scenario:exporting}': ['max>=0'],
    'http_req_failed{scenario:idle}': ['rate==0'],
    'http_req_failed{scenario:exporting}': ['rate==0'],
    checks: ['rate==1'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  teardownTimeout: `${WAIT_FOR_EXPORTS_SECONDS + 30}s`,
};

/** 측정 전에 존재하던 마지막 job id. 이번 테스트가 만든 작업만 골라내는 데 쓴다. */
export function setup() {
  waitForAppUp();
  const res = http.get(`${BASE_URL}/api/export-jobs?page=0&size=1`);
  check(res, { 'setup: 목록 200': (r) => r.status === 200 });
  const latest = res.json('content.0.jobId');
  return { baselineJobId: latest || 0 };
}

/** compose up 직후에 실행해도 측정이 실패하지 않도록 앱이 UP 이 될 때까지 기다린다 (최대 3분). */
function waitForAppUp() {
  for (let waited = 0; waited < 180; waited += 2) {
    const res = http.get(`${BASE_URL}/actuator/health`, { tags: { name: 'health' } });
    if (res.status === 200 && res.body && res.body.indexOf('"UP"') >= 0) {
      return;
    }
    sleep(2);
  }
  throw new Error('앱이 180초 안에 UP 이 되지 않았습니다');
}

export function readList() {
  const res = http.get(LIST_URL);
  check(res, { '목록 조회 200': (r) => r.status === 200 });
}

export function triggerExports() {
  for (let i = 0; i < EXPORT_COUNT; i++) {
    const res = http.post(`${BASE_URL}/api/export-jobs`, null, { tags: { name: 'create' } });
    check(res, { '생성 요청 202': (r) => r.status === 202 });
  }
}

/** 생성이 모두 끝나길 기다린 뒤, 실제로 exporting 구간 동안 워커가 돌았는지 출력한다. */
export function teardown(data) {
  let jobs = [];
  for (let waited = 0; waited < WAIT_FOR_EXPORTS_SECONDS; waited += 2) {
    const res = http.get(LIST_URL);
    jobs = (res.json('content') || []).filter((job) => job.jobId > data.baselineJobId);
    if (jobs.length >= EXPORT_COUNT && jobs.every((job) => job.status === 'done' || job.status === 'failed')) {
      break;
    }
    sleep(2);
  }

  const done = jobs.filter((job) => job.status === 'done');
  const starts = done.map((job) => toMillis(job.startedAt));
  const finishes = done.map((job) => toMillis(job.finishedAt));
  const busySeconds = done.length > 0 ? (Math.max(...finishes) - Math.min(...starts)) / 1000 : 0;

  console.log(`생성 작업: ${jobs.length}건 중 done ${done.length}건`);
  console.log(`워커 연속 처리 시간: ${busySeconds.toFixed(1)}s (exporting 측정 구간 30s)`);
}

export function handleSummary(data) {
  const lines = ['', '== 목록 조회 응답 시간 (ms)', row('구간', '요청 수', 'avg', 'med', 'p95', 'p99', 'max')];
  for (const phase of ['idle', 'exporting']) {
    const duration = data.metrics[`http_req_duration{scenario:${phase}}`];
    const failed = data.metrics[`http_req_failed{scenario:${phase}}`];
    if (!duration) {
      continue;
    }
    const v = duration.values;
    const count = failed ? failed.values.passes + failed.values.fails : '-';
    lines.push(row(phase, count, ms(v.avg), ms(v.med), ms(v['p(95)']), ms(v['p(99)']), ms(v.max)));
  }
  const checks = data.metrics.checks;
  if (checks) {
    lines.push('', `checks 통과율: ${(checks.values.rate * 100).toFixed(1)}%`);
  }
  return { stdout: `${lines.join('\n')}\n` };
}

function row(...cells) {
  return cells.map((cell, i) => String(cell).padStart(i === 0 ? 10 : 9)).join(' ');
}

function ms(value) {
  return value === undefined ? '-' : value.toFixed(1);
}

/** 서버의 KST LocalDateTime 문자열을 차이 계산용 밀리초로 변환 (두 값의 차이만 쓰므로 시간대는 무관) */
function toMillis(value) {
  return Date.parse(`${value.slice(0, 23)}Z`);
}

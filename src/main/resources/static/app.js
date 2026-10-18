'use strict';

/*
 * 엑셀 생성 작업 목록 페이지.
 *
 * 폴링 규칙
 *  - 진행 중(pending/processing) 작업이 보이면 2초 간격으로 갱신한다.
 *  - 진행 중 작업이 없으면 자동 갱신을 멈춘다 (유휴 페이지가 서버를 계속 호출하지 않도록).
 *    생성 요청, 새로고침 버튼, 탭 재표시 때 조회를 다시 시작한다.
 *  - 응답을 받은 뒤에 다음 요청을 예약하므로(setTimeout) 요청이 겹치지 않는다.
 *  - 탭이 숨겨지면 멈추고, 다시 보이면 즉시 갱신한다.
 *  - 실패가 이어지면 간격을 2배씩 늘린다 (최대 30초).
 *
 * 렌더링 규칙
 *  - 서버 값은 모두 textContent 로 넣는다 (innerHTML 미사용, XSS 방지).
 *  - 시간은 서버가 내려주는 KST 문자열을 그대로 보여 준다 (Date 변환 시 브라우저 시간대가 섞이므로).
 */

const API = '/api/export-jobs';
const PAGE_SIZE = 20;
const ACTIVE_INTERVAL_MS = 2000;
const MAX_ERROR_INTERVAL_MS = 30000;
const TOAST_MS = 2500;
const COLUMN_COUNT = 9;
const ACTIVE_STATUSES = new Set(['pending', 'processing']);
// 배지에는 명세의 상태 값(pending/processing/done/failed)을 그대로 보여 주고, 한국어 설명은 툴팁으로 제공한다
const STATUS_DESCRIPTIONS = {
  pending: '대기 중 — 접수되어 워커 실행을 기다리는 중',
  processing: '처리 중 — 엑셀 파일 생성 중',
  done: '완료 — 파일 생성 완료, 다운로드 가능',
  failed: '실패 — 상태를 눌러 실패 사유 확인',
};
const SVG_NS = 'http://www.w3.org/2000/svg';

const el = {
  createButton: document.getElementById('create-button'),
  createLabel: document.getElementById('create-label'),
  refreshButton: document.getElementById('refresh-button'),
  errorBanner: document.getElementById('error-banner'),
  liveIndicator: document.getElementById('live-indicator'),
  statusLine: document.getElementById('status-line'),
  totalCount: document.getElementById('total-count'),
  rows: document.getElementById('job-rows'),
  prevButton: document.getElementById('prev-button'),
  nextButton: document.getElementById('next-button'),
  pageInfo: document.getElementById('page-info'),
  toast: document.getElementById('toast'),
};

const state = {
  page: 0,
  totalPages: 0,
  timer: null,
  toastTimer: null,
  loading: false,
  reloadRequested: false,
  failures: 0,
};

// ---------- 목록 조회와 폴링 ----------

async function refresh() {
  clearTimeout(state.timer);
  if (state.loading) {
    // 진행 중인 조회가 끝나면 한 번 더 조회한다 (요청을 겹쳐 보내지 않음)
    state.reloadRequested = true;
    return;
  }
  state.loading = true;
  let nextDelay;
  try {
    const data = await fetchJson(`${API}?page=${state.page}&size=${PAGE_SIZE}`);
    if (data.totalPages > 0 && state.page >= data.totalPages) {
      state.page = data.totalPages - 1;
      state.reloadRequested = true;
    }
    render(data);
    state.failures = 0;
    hideError();
    nextDelay = hasActiveJob(data.content) ? ACTIVE_INTERVAL_MS : null;
    updateStatusLine(data.content);
  } catch (e) {
    state.failures += 1;
    nextDelay = Math.min(ACTIVE_INTERVAL_MS * 2 ** state.failures, MAX_ERROR_INTERVAL_MS);
    showError(`목록을 불러오지 못했습니다. ${Math.round(nextDelay / 1000)}초 후 다시 시도합니다. (${e.message})`);
    el.liveIndicator.className = 'live-indicator is-error';
    el.statusLine.textContent = '연결 끊김';
  } finally {
    state.loading = false;
  }

  if (state.reloadRequested) {
    state.reloadRequested = false;
    refresh();
    return;
  }
  schedule(nextDelay);
}

/** delay 가 null 이면 예약하지 않는다 (진행 중 작업 없음). */
function schedule(delay) {
  clearTimeout(state.timer);
  if (delay == null || document.hidden) {
    return;
  }
  state.timer = setTimeout(refresh, delay);
}

function hasActiveJob(jobs) {
  return jobs.some((job) => ACTIVE_STATUSES.has(job.status));
}

document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    clearTimeout(state.timer);
  } else {
    refresh();
  }
});

// ---------- 생성 요청 ----------

async function requestExport() {
  el.createButton.disabled = true;
  el.createButton.classList.add('is-loading');
  el.createLabel.textContent = '요청 중…';
  try {
    const job = await fetchJson(API, { method: 'POST' });
    hideError();
    showToast(`#${job.jobId} 작업이 접수되었습니다`);
    state.page = 0;
    refresh();
  } catch (e) {
    showError(`엑셀 생성 요청에 실패했습니다. (${e.message})`);
  } finally {
    el.createButton.disabled = false;
    el.createButton.classList.remove('is-loading');
    el.createLabel.textContent = '엑셀 생성 요청';
  }
}

// ---------- 렌더링 ----------

function render(data) {
  state.totalPages = data.totalPages;
  el.rows.replaceChildren(
    ...(data.content.length > 0 ? data.content.map(renderRow) : [renderEmptyRow()]),
  );

  const totalPages = Math.max(data.totalPages, 1);
  el.totalCount.textContent = `${data.totalElements.toLocaleString()}건`;
  el.pageInfo.textContent = `${data.page + 1} / ${totalPages} 페이지 · 전체 ${data.totalElements.toLocaleString()}건`;
  el.prevButton.disabled = state.page <= 0;
  el.nextButton.disabled = state.page >= totalPages - 1;
}

function renderRow(job) {
  const tr = document.createElement('tr');
  tr.append(
    td(span(String(job.jobId), 'job-id')),
    timeCell(job.createdAt),
    timeCell(job.startedAt),
    timeCell(job.finishedAt),
    elapsedCell(job.startedAt, job.finishedAt),
    statusCell(job),
    rowCountCell(job.rowCount),
    pathCell(job.filePath),
    downloadCell(job.downloadUrl),
  );
  return tr;
}

function renderEmptyRow() {
  const cell = td(
    paragraph('아직 요청된 작업이 없습니다', 'empty-title'),
    paragraph('"엑셀 생성 요청" 버튼을 눌러 첫 작업을 만들어 보세요.', 'empty-text'),
  );
  cell.className = 'empty';
  cell.colSpan = COLUMN_COUNT;
  const tr = document.createElement('tr');
  tr.append(cell);
  return tr;
}

/** "2026-10-17T14:30:05.123" -> 시각(14:30:05)을 크게, 날짜(2026-10-17)를 작게 두 줄로 */
function timeCell(value) {
  if (!value) {
    return placeholderCell();
  }
  const [date, time] = formatTime(value).split(' ');
  const cell = td(span(time, 'time-clock'), span(date, 'time-date'));
  cell.className = 'time';
  cell.title = formatTime(value);
  return cell;
}

function elapsedCell(startedAt, finishedAt) {
  const elapsed = formatElapsed(startedAt, finishedAt);
  if (elapsed === '-') {
    return placeholderCell('num');
  }
  const cell = td(span(elapsed, 'elapsed'));
  cell.className = 'num';
  return cell;
}

function statusCell(job) {
  const badge = span(job.status, `badge badge-${job.status}`);
  if (job.status !== 'failed' || !job.errorMessage) {
    badge.title = STATUS_DESCRIPTIONS[job.status] ?? '';
    return td(badge);
  }
  // 실패 사유는 키보드로도 열 수 있도록 <details> 로 제공한다 (title 툴팁은 마우스로만 보임)
  const summary = document.createElement('summary');
  summary.title = '실패 사유 보기';
  summary.append(badge);
  const details = document.createElement('details');
  details.className = 'failure';
  details.append(summary, paragraph(job.errorMessage, 'failure-message'));
  const cell = td(details);
  cell.className = 'status-cell';
  return cell;
}

function rowCountCell(rowCount) {
  if (rowCount == null) {
    return placeholderCell('num');
  }
  const cell = td(document.createTextNode(rowCount.toLocaleString()));
  cell.className = 'num';
  return cell;
}

function pathCell(filePath) {
  if (!filePath) {
    return placeholderCell();
  }
  const path = span(filePath, 'path');
  path.title = filePath;
  return td(path);
}

function downloadCell(downloadUrl) {
  if (!downloadUrl) {
    return placeholderCell('action');
  }
  const link = document.createElement('a');
  link.href = downloadUrl;
  link.className = 'download-link';
  link.setAttribute('download', '');
  link.append(downloadIcon(), document.createTextNode('다운로드'));
  const cell = td(link);
  cell.className = 'action';
  return cell;
}

function placeholderCell(className) {
  const cell = td(span('-', 'placeholder'));
  if (className) {
    cell.className = className;
  }
  return cell;
}

function td(...children) {
  const cell = document.createElement('td');
  cell.append(...children);
  return cell;
}

function span(text, className) {
  const node = document.createElement('span');
  node.className = className;
  node.textContent = text;
  return node;
}

function paragraph(text, className) {
  const node = document.createElement('p');
  node.className = className;
  node.textContent = text;
  return node;
}

function downloadIcon() {
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('viewBox', '0 0 16 16');
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS(SVG_NS, 'path');
  path.setAttribute('d', 'M8 2.5v8m0 0L4.5 7M8 10.5 11.5 7M3 13.5h10');
  path.setAttribute('fill', 'none');
  path.setAttribute('stroke', 'currentColor');
  path.setAttribute('stroke-width', '1.6');
  path.setAttribute('stroke-linecap', 'round');
  path.setAttribute('stroke-linejoin', 'round');
  svg.append(path);
  return svg;
}

function updateStatusLine(jobs) {
  const active = jobs.filter((job) => ACTIVE_STATUSES.has(job.status)).length;
  const progress = active > 0
    ? `진행 중 ${active}건 · ${ACTIVE_INTERVAL_MS / 1000}초마다 갱신`
    : '진행 중인 작업 없음 · 자동 갱신 멈춤';
  el.liveIndicator.className = active > 0 ? 'live-indicator is-active' : 'live-indicator';
  el.statusLine.textContent = `${progress} · 마지막 ${formatClock(new Date())}`;
}

// ---------- 표시 형식 ----------

/** "2026-10-17T14:30:05.123456" -> "2026-10-17 14:30:05" */
function formatTime(value) {
  if (!value) {
    return '-';
  }
  return value.replace('T', ' ').slice(0, 19);
}

/** 브라우저 현재 시각 -> "HH:mm:ss" (마지막 갱신 표시용) */
function formatClock(date) {
  return [date.getHours(), date.getMinutes(), date.getSeconds()]
    .map((n) => String(n).padStart(2, '0'))
    .join(':');
}

/** 두 시각이 같은 시간대(KST)이므로 차이만 계산한다. */
function formatElapsed(startedAt, finishedAt) {
  if (!startedAt || !finishedAt) {
    return '-';
  }
  const ms = toEpochMillis(finishedAt) - toEpochMillis(startedAt);
  if (!Number.isFinite(ms) || ms < 0) {
    return '-';
  }
  return `${(ms / 1000).toFixed(1)}초`;
}

/** 시간대 없는 문자열을 UTC 로 간주해 파싱한다 (차이 계산 전용). 마이크로초는 밀리초까지만 사용한다. */
function toEpochMillis(value) {
  return Date.parse(`${value.slice(0, 23)}Z`);
}

// ---------- 통신과 알림 ----------

async function fetchJson(url, options = {}) {
  const response = await fetch(url, {
    ...options,
    headers: { Accept: 'application/json' },
    cache: 'no-store',
  });
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    // 서버는 에러를 ProblemDetail(title, detail) 로 응답한다
    const reason = body?.detail ?? body?.title ?? `HTTP ${response.status}`;
    throw new Error(reason);
  }
  return body;
}

function showError(message) {
  el.errorBanner.textContent = message;
  el.errorBanner.hidden = false;
}

function hideError() {
  el.errorBanner.hidden = true;
  el.errorBanner.textContent = '';
}

function showToast(message) {
  clearTimeout(state.toastTimer);
  el.toast.textContent = message;
  el.toast.hidden = false;
  state.toastTimer = setTimeout(() => {
    el.toast.hidden = true;
  }, TOAST_MS);
}

// ---------- 시작 ----------

el.createButton.addEventListener('click', requestExport);
el.refreshButton.addEventListener('click', () => {
  // 클릭 피드백: 아이콘을 한 바퀴 돌린다
  el.refreshButton.classList.remove('is-spinning');
  void el.refreshButton.offsetWidth;
  el.refreshButton.classList.add('is-spinning');
  refresh();
});
el.prevButton.addEventListener('click', () => {
  if (state.page > 0) {
    state.page -= 1;
    refresh();
  }
});
el.nextButton.addEventListener('click', () => {
  if (state.page < state.totalPages - 1) {
    state.page += 1;
    refresh();
  }
});

refresh();

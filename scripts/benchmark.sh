#!/usr/bin/env bash
# =====================================================================
# 엑셀 생성 소요시간·메모리 측정
#
# 사용법 (프로젝트 루트에서, docker compose up --build -d 이후)
#   scripts/benchmark.sh                  10만 건, 5회
#   RUNS=3 ROWS=1000000 scripts/benchmark.sh   주문을 100만 건으로 늘려 3회
#
# - 작업은 한 건씩 차례로 요청한다 (이전 작업이 끝난 뒤 다음 요청). 순수 생성 시간을 재기 위함.
# - 소요시간은 DB 에 기록된 finished_at - started_at (워커가 실제로 처리한 시간).
# - 메모리는 두 가지로 본다.
#   1) 생성 중 샘플링한 최댓값 (컨테이너 안에서 0.2초 간격, 힙은 actuator 로 0.5초 간격)
#      anon  : JVM 프로세스가 실제로 쓰는 메모리 (힙 + 힙 밖)
#      cache : 페이지 캐시 (SXSSF 임시 파일·결과 파일 쓰기). 메모리가 부족하면 커널이 먼저 회수한다
#      heap  : JVM 힙 committed (OS 에서 받아 둔 힙 크기)
#   2) cgroup memory.peak: 컨테이너 시작 이후 anon + cache 합계의 최댓값
# - ROWS 로 늘린 주문은 기존 행을 반복 복사해 같은 분포를 유지한다.
#   원래 10만 건으로 돌아가려면 docker compose down -v 후 다시 up.
# - macOS 기본 bash(3.2)와 curl, docker 만으로 동작한다 (jq 불필요).
# =====================================================================
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
RUNS="${RUNS:-5}"
ROWS="${ROWS:-100000}"
JOB_TIMEOUT_SECONDS="${JOB_TIMEOUT_SECONDS:-600}"

psql_value() {
  docker compose exec -T db psql -U exporter -d exporter -tAc "$1" | tr -d '[:space:]'
}

json_field() {
  sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p"
}

to_mib() {
  awk -v b="$1" 'BEGIN { printf "%.0f", b / 1024 / 1024 }'
}

heap_metric() {
  curl -fsS --max-time 2 "${BASE_URL}/actuator/metrics/jvm.memory.$1?tag=area:heap" 2>/dev/null \
    | sed -n 's/.*"value":\([0-9.Ee+-]*\).*/\1/p'
}

max_of() {
  awk -v a="$1" -v b="$2" 'BEGIN { if (b == "" || a + 0 >= b + 0) print a; else print b }'
}

SAMPLE_DIR=$(mktemp -d)
trap 'docker compose exec -T app touch /tmp/benchmark-stop >/dev/null 2>&1 || true; rm -rf "${SAMPLE_DIR}"' EXIT

# 컨테이너 안에서 memory.stat 의 anon/file 을 0.2초 간격으로 출력한다.
# docker 클라이언트를 kill 해도 컨테이너 안의 루프는 남을 수 있으므로, 정지 파일로 스스로 끝나게 한다.
start_sampler() {
  docker compose exec -T app rm -f /tmp/benchmark-stop
  docker compose exec -T app sh -c '
    i=0
    while [ ! -e /tmp/benchmark-stop ] && [ "$i" -lt 6000 ]; do
      grep -E "^(anon|file) " /sys/fs/cgroup/memory.stat
      sleep 0.2
      i=$((i + 1))
    done
    rm -f /tmp/benchmark-stop' > "$1" &
  SAMPLER_PID=$!
}

stop_sampler() {
  docker compose exec -T app touch /tmp/benchmark-stop
  wait "${SAMPLER_PID}" 2>/dev/null || true
}

echo "== 환경"
echo "host: $(uname -sm), runs: ${RUNS}, target rows: ${ROWS}"

echo "== 앱 기동 대기"
deadline=$((SECONDS + 180))
until curl -fsS --max-time 5 "${BASE_URL}/actuator/health" 2>/dev/null | grep -q '"UP"'; do
  if [ "${SECONDS}" -ge "${deadline}" ]; then
    echo "앱이 180초 안에 UP 이 되지 않았습니다" >&2
    exit 1
  fi
  sleep 2
done
echo "UP"

current=$(psql_value "SELECT count(*) FROM orders")
if [ "${ROWS}" -gt "${current}" ]; then
  need=$((ROWS - current))
  echo "== 주문 ${current}건 -> ${ROWS}건으로 확장 (${need}건 추가)"
  docker compose exec -T db psql -U exporter -d exporter -q -c "
    INSERT INTO orders (user_name, product_name, category, amount, status, order_date)
    SELECT o.user_name, o.product_name, o.category, o.amount, o.status, o.order_date
    FROM generate_series(1, ${need}) AS g
    JOIN orders o ON o.id = ((g - 1) % ${current}) + 1
    ORDER BY g;
    ANALYZE orders;"
fi
rows_in_db=$(psql_value "SELECT count(*) FROM orders")
echo "orders: ${rows_in_db}건"

echo "== 측정 (${RUNS}회, 순차)"
printf "%-4s %-7s %-7s %10s %10s %10s %11s %10s\n" \
  "run" "job_id" "status" "elapsed(s)" "file(MiB)" "anon max" "cache max" "heap max"
results=""
anon_all=""
for run in $(seq 1 "${RUNS}"); do
  samples="${SAMPLE_DIR}/run-${run}.txt"
  start_sampler "${samples}"
  heap_max=""

  created=$(curl -fsS --max-time 10 -X POST "${BASE_URL}/api/export-jobs")
  job_id=$(printf '%s' "${created}" | json_field jobId)

  deadline=$((SECONDS + JOB_TIMEOUT_SECONDS))
  status=""
  while :; do
    heap=$(heap_metric committed || true)
    [ -n "${heap}" ] && heap_max=$(max_of "${heap}" "${heap_max}")
    status=$(curl -fsS --max-time 5 "${BASE_URL}/api/export-jobs/${job_id}" | json_field status || true)
    if [ "${status}" = "done" ] || [ "${status}" = "failed" ]; then
      break
    fi
    if [ "${SECONDS}" -ge "${deadline}" ]; then
      status="timeout"
      break
    fi
    sleep 0.5
  done
  stop_sampler

  anon_max=$(awk '$1 == "anon" && $2 + 0 > m { m = $2 + 0 } END { print m + 0 }' "${samples}")
  cache_max=$(awk '$1 == "file" && $2 + 0 > m { m = $2 + 0 } END { print m + 0 }' "${samples}")
  anon_all=$(max_of "${anon_max}" "${anon_all}")

  elapsed=$(psql_value "SELECT round(extract(epoch FROM finished_at - started_at)::numeric, 2) FROM export_job WHERE id = ${job_id}")
  size_mib="-"
  if [ "${status}" = "done" ]; then
    bytes=$(docker compose exec -T app stat -c %s "/data/exports/${job_id}.xlsx" | tr -d '[:space:]')
    size_mib=$(awk -v b="${bytes}" 'BEGIN { printf "%.1f", b / 1024 / 1024 }')
    results="${results} ${elapsed}"
  fi
  printf "%-4s %-7s %-7s %10s %10s %10s %11s %10s\n" \
    "${run}" "${job_id}" "${status}" "${elapsed:--}" "${size_mib}" \
    "$(to_mib "${anon_max}")MiB" "$(to_mib "${cache_max}")MiB" "$(to_mib "${heap_max:-0}")MiB"
done

echo "== 요약"
if [ -z "${results}" ]; then
  echo "완료된 작업 없음 (docker compose logs app 으로 원인 확인)"
fi
[ -n "${results}" ] && printf '%s\n' ${results} | awk -v rows="${rows_in_db}" '
  NR == 1 { first = $1 }
  NR > 1  { sum += $1; n++ }
  END {
    printf "첫 회(JIT 워밍업 포함): %.2fs (%.0f rows/s)\n", first, rows / first
    if (n > 0) printf "이후 평균 (%d회):      %.2fs (%.0f rows/s)\n", n, sum / n, rows / (sum / n)
  }'

peak=$(docker compose exec -T app cat /sys/fs/cgroup/memory.peak | tr -d '[:space:]')
limit=$(docker compose exec -T app cat /sys/fs/cgroup/memory.max | tr -d '[:space:]')
percent() {
  awk -v p="$1" -v l="${limit}" 'BEGIN { printf "%.0f", p * 100 / l }'
}
echo "생성 중 anon 최댓값 (JVM 실사용):       $(to_mib "${anon_all:-0}")MiB / $(to_mib "${limit}")MiB ($(percent "${anon_all:-0}")%)"
echo "cgroup memory.peak (anon + 페이지 캐시): $(to_mib "${peak}")MiB / $(to_mib "${limit}")MiB ($(percent "${peak}")%)"

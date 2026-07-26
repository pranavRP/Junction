#!/usr/bin/env bash
# Phase 0 baseline — MEA-001 (through the proxy) and MEA-002 (direct control).
#
# R-2: 5 runs minimum, report median + spread. R-44: this script IS the exact
# command, so the benchmark can be rerun from the doc.
#
# Usage:  bash tools/bench/phase0-baseline.sh
set -uo pipefail

RUNS="${RUNS:-5}"
DUR="${DUR:-15s}"
THREADS="${THREADS:-4}"
CONNS="${CONNS:-64}"

wrk_run() {  # $1 = url
  docker compose --profile load run --rm --no-deps wrk \
    -t"$THREADS" -c"$CONNS" -d"$DUR" --latency "$1" 2>/dev/null
}

bench() {  # $1 = label, $2 = url
  echo "### $1  ($THREADS threads, $CONNS conns, $DUR, ${RUNS} runs)  -> $2"
  echo "--- warmup (JIT) ---"
  wrk_run "$2" | grep -E "Requests/sec" || true
  for i in $(seq 1 "$RUNS"); do
    out=$(wrk_run "$2")
    rps=$(echo "$out"  | awk '/Requests\/sec/ {print $2}')
    p50=$(echo "$out"  | awk '/^ *50%/ {print $2}')
    p90=$(echo "$out"  | awk '/^ *90%/ {print $2}')
    p99=$(echo "$out"  | awk '/^ *99%/ {print $2}')
    echo "run $i: rps=$rps p50=$p50 p90=$p90 p99=$p99"
  done
  echo
}

bench "MEA-001 through junction" "http://junction:8080/"
bench "MEA-002 direct to backend (control)" "http://backend-1:8000/"

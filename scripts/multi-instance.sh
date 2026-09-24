#!/usr/bin/env bash
# Runs MultiInstanceIT (production locking) and SynchronizedControlIT (Java lock) against
# docker compose with three API replicas. Needs .env (see .env.example).
# The tests run in a Maven container on the compose network, so the command is the same on
# Windows (Git Bash) and in CI. M2_MOUNT: named volume locally, the runner's ~/.m2 in CI.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd -W 2>/dev/null || pwd)"   # D:/... under Git Bash, /home/... on Linux
M2_MOUNT="${M2_MOUNT:-wallet-m2:/root/.m2}"
export NGINX_EXPOSE_UPSTREAM=on

trap 'docker compose down -v >/dev/null 2>&1 || true' EXIT

run_scenario() {
  local mutator=$1 test=$2
  echo "=== $test against APP_LEDGER_MUTATOR=$mutator, 3 replicas"
  APP_LEDGER_MUTATOR=$mutator docker compose up -d --build --scale api=3 --wait
  MSYS_NO_PATHCONV=1 docker run --rm --network wallet-ledger_default --env-file .env \
    -e MI_MUTATOR="$mutator" -v "$ROOT/backend:/app" -v "$M2_MOUNT" -w /app \
    maven:3.9-eclipse-temurin-21 \
    mvn -B verify -Pmulti-instance "-Dit.test=$test" -DfailIfNoSpecifiedTests=false
  docker compose down -v
}

run_scenario pessimistic MultiInstanceIT
run_scenario synchronized SynchronizedControlIT
echo "multi-instance: both scenarios passed"

#!/usr/bin/env bash
# Usage: scripts/smoke.sh http://localhost:8095
# Checks a running stack end to end through nginx. Exits non-zero on the first failed check.
set -euo pipefail
BASE="${1:?base url required}"
USER_NAME="smoke-$(date +%s)"
PASS="smoke-test-password"

check() { if [ "$2" != "$3" ]; then echo "FAIL: $1 (expected $3, got $2)"; exit 1; fi; echo "ok: $1"; }
field() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }

check "healthz" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/healthz")" 200
check "api refuses anonymous" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/wallet")" 401
check "security header" "$(curl -sI "$BASE/" | grep -ci '^x-frame-options: DENY')" 1

body="{\"username\":\"$USER_NAME\",\"password\":\"$PASS\"}"
check "register" "$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d "$body" "$BASE/api/v1/auth/register")" 201
TOKEN="$(curl -s -H 'Content-Type: application/json' -d "$body" "$BASE/api/v1/auth/login" | field accessToken)"
KEY="$(cat /proc/sys/kernel/random/uuid 2>/dev/null || date +%s%N)"
check "deposit" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' -d '{"amount":"10.0000"}' "$BASE/api/v1/wallet/deposits")" 201
check "balance" "$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/wallet" | field balance)" 10.0000
check "statement paging shape" "$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/statement" | grep -c '"totalPages"')" 1
echo "smoke test passed"

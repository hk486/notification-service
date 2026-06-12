#!/usr/bin/env bash
# test-all.sh — end-to-end test for all 14 days
# Prereqs: app on :8080, MySQL on :3307
# Usage:   bash test-all.sh

BASE="http://localhost:8080"
PASS=0; FAIL=0
TS=$(date +%s)

green() { printf '\033[32m✓ %s\033[0m\n' "$*"; }
red()   { printf '\033[31m✗ %s\033[0m\n' "$*"; }
blue()  { printf '\033[34m\n▶ %s\033[0m\n' "$*"; }

check() {
  if echo "$3" | grep -q "$2"; then
    green "$1"; PASS=$((PASS+1))
  else
    red "$1  (want='$2'  got='$3')"; FAIL=$((FAIL+1))
  fi
}

post()  { curl -s -X POST   "$BASE$1" -H 'Content-Type: application/json' -d "$2"; }
put()   { curl -s -X PUT    "$BASE$1" -H 'Content-Type: application/json' -d "$2"; }
get()   { curl -s           "$BASE$1"; }
spost() { curl -s -o /dev/null -w '%{http_code}' -X POST   "$BASE$1" -H 'Content-Type: application/json' -d "$2"; }
sdel()  { curl -s -o /dev/null -w '%{http_code}' -X DELETE "$BASE$1"; }
db()    { mysql -uuser -ppassword -h127.0.0.1 -P3307 notification_service -sNe "$1" 2>/dev/null; }

# ── DAYS 1-7 ─────────────────────────────────────────────────────────────────
blue "DAYS 1-7 | Core API"

K1="d1-${TS}"
R=$(post /api/v1/notifications "{\"userId\":\"u1\",\"channel\":\"SMS\",\"priority\":\"HIGH\",\"templateName\":\"OTP\",\"templateData\":{\"otp\":\"111111\"},\"idempotencyKey\":\"${K1}\"}")
check "Send SMS via OTP template -> 202" '"status":"ACCEPTED"' "$R"

SC=$(spost /api/v1/notifications "{\"userId\":\"u1\",\"channel\":\"SMS\",\"priority\":\"HIGH\",\"message\":\"dup\",\"idempotencyKey\":\"${K1}\"}")
check "Duplicate key -> 409" "409" "$SC"

SC=$(spost /api/v1/notifications "{\"userId\":\"u1\",\"channel\":\"SMS\",\"priority\":\"HIGH\",\"idempotencyKey\":\"d1-bad-${TS}\"}")
check "Missing message + template -> 400" "400" "$SC"

SC=$(spost /api/v1/notifications "{\"userId\":\"u1\",\"channel\":\"EMAIL\",\"priority\":\"HIGH\",\"templateName\":\"GHOST\",\"idempotencyKey\":\"d1-ghost-${TS}\"}")
check "Unknown template -> 404" "404" "$SC"

check "GET history returns data" '"idempotencyKey"' "$(get /api/v1/notifications/u1)"

# ── DAY 7 ─────────────────────────────────────────────────────────────────────
blue "DAY 7 | Rate limiter"

RU="rl-${TS}"
for i in 1 2 3 4 5; do
  spost /api/v1/notifications "{\"userId\":\"${RU}\",\"channel\":\"EMAIL\",\"priority\":\"LOW\",\"message\":\"p\",\"idempotencyKey\":\"rl-${i}-${TS}\"}" > /dev/null
done
SC=$(spost /api/v1/notifications "{\"userId\":\"${RU}\",\"channel\":\"EMAIL\",\"priority\":\"LOW\",\"message\":\"over\",\"idempotencyKey\":\"rl-6-${TS}\"}")
check "6th request -> 429" "429" "$SC"

# ── DAY 8 ─────────────────────────────────────────────────────────────────────
blue "DAY 8 | Template engine"

check "ORDER template (EMAIL) -> 202" '"ACCEPTED"' \
  "$(post /api/v1/notifications "{\"userId\":\"tmpl\",\"channel\":\"EMAIL\",\"priority\":\"MEDIUM\",\"templateName\":\"ORDER\",\"templateData\":{\"orderId\":\"O1\",\"status\":\"SHIPPED\"},\"idempotencyKey\":\"d8a-${TS}\"}")"

check "PROMO template (PUSH) -> 202" '"ACCEPTED"' \
  "$(post /api/v1/notifications "{\"userId\":\"tmpl\",\"channel\":\"PUSH\",\"priority\":\"LOW\",\"templateName\":\"PROMO\",\"templateData\":{\"name\":\"Dev\",\"offer\":\"50off\"},\"idempotencyKey\":\"d8b-${TS}\"}")"

# ── DAY 9 ─────────────────────────────────────────────────────────────────────
blue "DAY 9 | User preferences & opt-out"

check "PUT opt-out SMS -> false" '"enabled":false' \
  "$(put /api/v1/preferences/pref-user/SMS '{"enabled":false}')"
check "GET preferences returns row" '"channel":"SMS"' \
  "$(get /api/v1/preferences/pref-user)"

R=$(post /api/v1/notifications "{\"userId\":\"pref-user\",\"channel\":\"SMS\",\"priority\":\"HIGH\",\"message\":\"skip\",\"idempotencyKey\":\"d9-${TS}\"}")
check "SMS to opted-out user -> 202" '"ACCEPTED"' "$R"
echo "  Waiting 4s for consumer..."; sleep 4
check "log.status = SKIPPED (opt-out)" "SKIPPED" \
  "$(db "SELECT status FROM notification_logs WHERE idempotency_key='d9-${TS}'")"

blue "DAY 9 | DND window"

check "PUT DND all-day -> stored" '"dndStart"' \
  "$(put /api/v1/preferences/dnd-user/EMAIL '{"enabled":true,"dndStart":"00:00","dndEnd":"23:59"}')"
R=$(post /api/v1/notifications "{\"userId\":\"dnd-user\",\"channel\":\"EMAIL\",\"priority\":\"MEDIUM\",\"message\":\"dnd\",\"idempotencyKey\":\"dnd-${TS}\"}")
check "EMAIL in DND -> 202" '"ACCEPTED"' "$R"
sleep 4
check "log.status = SKIPPED (DND)" "SKIPPED" \
  "$(db "SELECT status FROM notification_logs WHERE idempotency_key='dnd-${TS}'")"

# ── DAY 11 ────────────────────────────────────────────────────────────────────
blue "DAY 11 | Metrics & Observability"

R=$(get /actuator/prometheus)
check "Prometheus has notifications_accepted_total" "notifications_accepted_total" "$R"
check "Prometheus has notifications_sent_total"     "notifications_sent_total"     "$R"
check "Health = UP" '"status":"UP"' "$(get /actuator/health)"

# ── DAY 12 ────────────────────────────────────────────────────────────────────
blue "DAY 12 | Scheduled notifications"

FUTURE=$(date -d '+5 minutes' '+%Y-%m-%dT%H:%M:%S')
R=$(post /api/v1/scheduled "{\"userId\":\"sched\",\"channel\":\"EMAIL\",\"priority\":\"MEDIUM\",\"templateName\":\"WELCOME\",\"templateData\":{\"name\":\"Test\",\"app\":\"App\"},\"idempotencyKey\":\"sched-${TS}\",\"scheduledAt\":\"${FUTURE}\"}")
check "POST /scheduled -> PENDING" '"status":"PENDING"' "$R"
SCHED_ID=$(echo "$R" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null)

check "GET /scheduled returns row" "sched-${TS}" "$(get /api/v1/scheduled/sched)"

SC=$(sdel "/api/v1/scheduled/${SCHED_ID}"); check "DELETE -> 204"      "204" "$SC"
SC=$(sdel "/api/v1/scheduled/${SCHED_ID}"); check "DELETE again -> 409" "409" "$SC"

PAST=$(date -d '-1 second' '+%Y-%m-%dT%H:%M:%S')
SC=$(spost /api/v1/scheduled "{\"userId\":\"sched\",\"channel\":\"EMAIL\",\"priority\":\"LOW\",\"message\":\"past\",\"idempotencyKey\":\"sched-past-${TS}\",\"scheduledAt\":\"${PAST}\"}")
check "Past scheduledAt -> 400" "400" "$SC"

blue "DAY 12 | Auto-dispatch"

db "INSERT IGNORE INTO scheduled_notifications
     (user_id,channel,priority,template_name,template_data,idempotency_key,scheduled_at,status,created_at)
   VALUES ('auto','EMAIL','HIGH','WELCOME','{\"name\":\"Auto\",\"app\":\"App\"}',
     'auto-${TS}', DATE_SUB(NOW(),INTERVAL 1 MINUTE), 'PENDING', NOW())"

echo "  Waiting up to 65s for @Scheduled to fire..."
for i in $(seq 1 13); do
  sleep 5
  DSTATUS=$(db "SELECT status FROM scheduled_notifications WHERE idempotency_key='auto-${TS}'")
  [ "$DSTATUS" = "DISPATCHED" ] && break
done
check "Past-due row -> DISPATCHED" "DISPATCHED" "$DSTATUS"
check "notification_log created"  "SENT\|PENDING" \
  "$(db "SELECT status FROM notification_logs WHERE idempotency_key='auto-${TS}'")"

# ── DAY 13 ────────────────────────────────────────────────────────────────────
blue "DAY 13 | Webhook callbacks"

R=$(post /api/v1/notifications "{\"userId\":\"wh\",\"channel\":\"EMAIL\",\"priority\":\"HIGH\",\"templateName\":\"WELCOME\",\"templateData\":{\"name\":\"WH\",\"app\":\"App\"},\"idempotencyKey\":\"wh-${TS}\",\"callbackUrl\":\"http://localhost:19999/cb\"}")
check "POST with callbackUrl -> 202" '"ACCEPTED"' "$R"
sleep 3
check "callbackUrl persisted in DB" "localhost:19999" \
  "$(db "SELECT callback_url FROM notification_logs WHERE idempotency_key='wh-${TS}'")"

SC=$(spost /api/v1/notifications "{\"userId\":\"wh\",\"channel\":\"EMAIL\",\"priority\":\"HIGH\",\"message\":\"x\",\"idempotencyKey\":\"wh-bad-${TS}\",\"callbackUrl\":\"ftp://invalid\"}")
check "Invalid callbackUrl -> 400" "400" "$SC"

# ── DAY 14 ────────────────────────────────────────────────────────────────────
blue "DAY 14 | Real provider health indicator"

R=$(get /actuator/health)
check "providers component present" '"providers"' "$R"
check "providers status=UP"        '"status":"UP"' "$R"
check "mode = MOCK"                'MOCK'          "$R"

# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "==========================================="
if [ $FAIL -eq 0 ]; then
  green "ALL $PASS TESTS PASSED"
else
  red "$FAIL FAILED / $PASS passed"
fi
echo "==========================================="
[ $FAIL -eq 0 ]

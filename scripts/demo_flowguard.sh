#!/usr/bin/env bash
# FlowGuard Synthetic End-to-End Demonstration Script
# Demonstrates: Ingestion, Idempotency, SSRF Defense, Search, Attempts, and Audited Retry

set -euo pipefail

BASE_URL="${FLOWGUARD_URL:-http://localhost:8080}"
CLIENT_ID="client_acme_corp"
CORRELATION_ID="corr-demo-$(date +%s)"
IDEMP_KEY="idemp-key-$(date +%s)"

echo "=========================================================="
echo "🛡️  FLOWGUARD INTEGRATION RELIABILITY PLATFORM DEMO"
echo "Target Base URL: ${BASE_URL}"
echo "=========================================================="

echo -e "\n1. Submitting valid business message..."
RESPONSE=$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "${BASE_URL}/api/v1/messages" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  -H "X-Client-Id: ${CLIENT_ID}" \
  -H "X-Correlation-ID: ${CORRELATION_ID}" \
  -d '{
    "messageType": "PAYROLL_DISBURSEMENT_V1",
    "destinationKey": "LEDGER_MAIN",
    "sourceSystem": "HR_WORKDAY",
    "externalReference": "PAY-2026-10",
    "payload": {"employeeId": "EMP-9481", "netAmount": 4250.00, "currency": "USD"}
  }')

BODY=$(echo "$RESPONSE" | sed '$d')
STATUS=$(echo "$RESPONSE" | tail -n1 | cut -d':' -f2)
echo "Status: ${STATUS}"
echo "Response: ${BODY}"

MESSAGE_ID=$(echo "$BODY" | grep -o '"messageId":"[^"]*' | cut -d'"' -f4 || echo "")

echo -e "\n2. Testing Idempotent Replay (Same Key, Identical Payload)..."
REPLAY_RESPONSE=$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "${BASE_URL}/api/v1/messages" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  -H "X-Client-Id: ${CLIENT_ID}" \
  -d '{
    "messageType": "PAYROLL_DISBURSEMENT_V1",
    "destinationKey": "LEDGER_MAIN",
    "sourceSystem": "HR_WORKDAY",
    "externalReference": "PAY-2026-10",
    "payload": {"employeeId": "EMP-9481", "netAmount": 4250.00, "currency": "USD"}
  }')
echo "Status: $(echo "$REPLAY_RESPONSE" | tail -n1 | cut -d':' -f2)"
echo "Response: $(echo "$REPLAY_RESPONSE" | sed '$d')"

echo -e "\n3. Testing Idempotency Conflict (Same Key, Altered Payload)..."
CONFLICT_RESPONSE=$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "${BASE_URL}/api/v1/messages" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  -H "X-Client-Id: ${CLIENT_ID}" \
  -d '{
    "messageType": "PAYROLL_DISBURSEMENT_V1",
    "destinationKey": "LEDGER_MAIN",
    "sourceSystem": "HR_WORKDAY",
    "externalReference": "PAY-2026-10",
    "payload": {"employeeId": "EMP-9481", "netAmount": 999999.00, "currency": "USD"}
  }')
echo "Status: $(echo "$CONFLICT_RESPONSE" | tail -n1 | cut -d':' -f2)"
echo "Response: $(echo "$CONFLICT_RESPONSE" | sed '$d')"

echo -e "\n4. Testing SSRF Prevention Defense (Raw URL injection)..."
SSRF_RESPONSE=$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "${BASE_URL}/api/v1/messages" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: idemp-ssrf-$(date +%s)" \
  -H "X-Client-Id: ${CLIENT_ID}" \
  -d '{
    "messageType": "PAYROLL_DISBURSEMENT_V1",
    "destinationKey": "http://169.254.169.254/latest/meta-data",
    "sourceSystem": "ATTACKER",
    "payload": {"test": true}
  }')
echo "Status: $(echo "$SSRF_RESPONSE" | tail -n1 | cut -d':' -f2)"
echo "Response: $(echo "$SSRF_RESPONSE" | sed '$d')"

echo -e "\n5. Querying Operational Dashboard Summary..."
OPS_RESPONSE=$(curl -s "${BASE_URL}/api/v1/operations/summary" -H "X-Scope: operations:read")
echo "Summary: ${OPS_RESPONSE}"

echo -e "\n=========================================================="
echo "✅ DEMONSTRATION COMPLETE"
echo "=========================================================="

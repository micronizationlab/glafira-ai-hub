#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
renderer="$repo_root/scripts/ci/render-dispatcher-pr-status.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/prs.json" <<'JSON'
[
  {"number":553,"title":"current exact head","headRefName":"docs/reconcile","headRefOid":"sha-current","isDraft":false,"url":"https://example.test/pr/553","statusCheckRollup":[]},
  {"number":554,"title":"stale only","headRefName":"feature/stale","headRefOid":"sha-new","isDraft":true,"url":"https://example.test/pr/554","statusCheckRollup":[]},
  {"number":555,"title":"rollup preferred","headRefName":"feature/rollup","headRefOid":"sha-rollup","isDraft":false,"url":"https://example.test/pr/555","statusCheckRollup":[{"name":"required","status":"COMPLETED","conclusion":"SUCCESS","detailsUrl":"https://example.test/check"}]}
]
JSON

cat > "$tmp/runs.json" <<'JSON'
[
  {"headSha":"sha-current","workflowName":"Android CI","name":"Android CI","status":"completed","conclusion":"success","createdAt":"2026-11-24T12:00:00Z","url":"https://example.test/run/1"},
  {"headSha":"sha-current","workflowName":"Dispatcher","name":"Dispatcher","status":"completed","conclusion":"success","createdAt":"2026-11-24T12:01:00Z","url":"https://example.test/run/2"},
  {"headSha":"sha-current","workflowName":"Android CI","name":"Android CI","status":"completed","conclusion":"failure","createdAt":"2026-11-24T11:00:00Z","url":"https://example.test/run/old"},
  {"headSha":"sha-other","workflowName":"Android CI","name":"Android CI","status":"completed","conclusion":"success","createdAt":"2026-11-24T12:02:00Z","url":"https://example.test/run/stale"}
]
JSON

output="$(bash "$renderer" "$tmp/prs.json" "$tmp/runs.json")"
grep -q 'Android CI: COMPLETED/success' <<< "$output"
grep -q 'Dispatcher: COMPLETED/success' <<< "$output"
! grep -q 'COMPLETED/failure' <<< "$output"
grep -q 'NO MATCHING RUN FOR EXACT HEAD' <<< "$output"
grep -q 'required: COMPLETED/SUCCESS' <<< "$output"

unknown_output="$(bash "$renderer" "$tmp/prs.json" <(printf 'null'))"
grep -q 'UNKNOWN / ACTIONS API UNAVAILABLE' <<< "$unknown_output"

empty_output="$(bash "$renderer" <(printf '[]') "$tmp/runs.json")"
[[ -z "$empty_output" ]]

echo "dispatcher status renderer fixtures: PASS"

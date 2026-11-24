#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <pull-requests.json> <workflow-runs.json>" >&2
  exit 2
fi

prs_file="$1"
runs_file="$2"
prs="$(cat "$prs_file")"
runs="$(cat "$runs_file")"

if ! jq -e 'type == "array"' >/dev/null 2>&1 <<< "$prs"; then
  echo "pull request input must be a JSON array" >&2
  exit 2
fi

while IFS= read -r pr; do
  number="$(jq -r '.number' <<< "$pr")"
  title="$(jq -r '.title // ""' <<< "$pr" | tr '|' '/')"
  branch="$(jq -r '.headRefName // ""' <<< "$pr")"
  sha="$(jq -r '.headRefOid // ""' <<< "$pr")"
  draft="$(jq -r '.isDraft // false' <<< "$pr")"
  url="$(jq -r '.url // ""' <<< "$pr")"
  checks="$(jq -c '.statusCheckRollup // []' <<< "$pr")"

  if [[ "$(jq 'length' <<< "$checks")" -gt 0 ]]; then
    ci="$(jq -r 'map("\(.name // .context // "check"): \(.status // .state // "unknown")/\(.conclusion // "pending") ([check](\(.detailsUrl // .targetUrl // "")))") | join("<br>")' <<< "$checks")"
  elif ! jq -e 'type == "array"' >/dev/null 2>&1 <<< "$runs"; then
    ci="UNKNOWN / ACTIONS API UNAVAILABLE"
  else
    matching="$(jq -c --arg sha "$sha" '[.[] | select(.headSha == $sha)] | sort_by(.createdAt // "") | reverse | group_by(.workflowName // .name // "unknown") | map(.[0])' <<< "$runs")"
    if [[ "$(jq 'length' <<< "$matching")" -eq 0 ]]; then
      ci="NO MATCHING RUN FOR EXACT HEAD"
    else
      ci="$(jq -r 'map(
        (.workflowName // .name // "workflow") + ": " +
        (if .status == "queued" then "QUEUED"
         elif .status == "in_progress" then "IN_PROGRESS"
         elif .status == "completed" then ("COMPLETED/" + (.conclusion // "UNKNOWN"))
         else "UNKNOWN/" + (.status // "missing") end) +
        " ([run](\(.url // "")))"
      ) | join("<br>")' <<< "$matching")"
    fi
  fi

  short_sha="${sha:0:12}"
  echo "| [#$number]($url) $title | $branch | <code>$short_sha</code> | $ci | $draft |"
done < <(jq -c '.[]' <<< "$prs")

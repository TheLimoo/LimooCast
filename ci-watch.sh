#!/usr/bin/env bash
# Watch the LimooCast CI run for the current HEAD, then download the APK
# artifact. Token comes from $GH_TOKEN in the environment only — it is
# never written to disk by this script.
set -u
REPO="TheLimoo/LimooCast"
SHA="$(cd ~/workspace/limoocast && git rev-parse HEAD)"
OUT="$HOME/workspace/limoocast/build-out"
mkdir -p "$OUT"
AUTH=(-H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github+json")
echo "HEAD $SHA"
RUN_ID=""
for i in $(seq 1 40); do
  sleep 30
  RUN_JSON="$(curl -s -m 20 "${AUTH[@]}" "https://api.github.com/repos/$REPO/actions/runs?head_sha=$SHA&per_page=5")"
  RUN_ID="$(echo "$RUN_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['workflow_runs'][0]['id'] if d.get('workflow_runs') else '')" 2>/dev/null)"
  [ -z "$RUN_ID" ] && { echo "poll $i: run not started yet"; continue; }
  STATUS="$(echo "$RUN_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); r=d['workflow_runs'][0]; print(r['status']+'/'+str(r.get('conclusion')))" 2>/dev/null)"
  echo "poll $i: run $RUN_ID $STATUS"
  [ "${STATUS%%/*}" = "completed" ] && break
done
[ -z "$RUN_ID" ] && { echo "ERROR: no CI run found for HEAD"; exit 1; }
CONC="$(curl -s -m 20 "${AUTH[@]}" "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID" | python3 -c "import sys,json; print(json.load(sys.stdin).get('conclusion'))")"
echo "conclusion: $CONC"
if [ "$CONC" != "success" ]; then
  curl -s -m 20 "${AUTH[@]}" "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID/jobs?per_page=20" | python3 -c "
import sys,json
for j in json.load(sys.stdin).get('jobs',[]):
    print(j['name'], j['conclusion'])
    for s in j.get('steps',[]):
        if s.get('conclusion') not in ('success','skipped',None): print('  FAILED STEP:', s['name'])
"
  exit 1
fi
ART_ID="$(curl -s -m 20 "${AUTH[@]}" "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID/artifacts" | python3 -c "import sys,json; a=json.load(sys.stdin)['artifacts']; print([x['id'] for x in a if x['name']=='limoocast-debug'][0])")"
curl -sL -m 60 "${AUTH[@]}" "https://api.github.com/repos/$REPO/actions/artifacts/$ART_ID/zip" -o "$OUT/artifact.zip"
unzip -o -q "$OUT/artifact.zip" -d "$OUT"
ls -l "$OUT"
find "$OUT" -name "*.apk" -exec sha256sum {} \;
find "$OUT" -name "*.apk" -exec sh -c 'unzip -l "$1" | grep -E "AndroidManifest|classes.*dex" | head -5' _ {} \;
echo "CI WATCH DONE: success"

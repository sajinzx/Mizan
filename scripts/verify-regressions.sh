#!/usr/bin/env bash
# Plants each known defect (spec §7) back into the code, runs its named regression test, and
# restores the file. Every plant must make its test FAIL (not merely break compilation).
# Usage: scripts/verify-regressions.sh   (from the repository root)
set -u
tool="$(dirname "$0")/plant_regressions.py"
n=$(python "$tool" count); all_ok=1
for i in $(seq 0 $((n-1))); do
  IFS='|' read -r name task test <<<"$(python "$tool" info "$i")"
  python "$tool" apply "$i" || { echo "$name -> APPLY FAILED"; all_ok=0; continue; }
  out=$(./gradlew "$task" --tests "$test" 2>&1)
  python "$tool" restore "$i"
  method="${test##*.}"
  if echo "$out" | grep -q 'compileJava FAILED\|compileTestJava FAILED'; then echo "$name -> COMPILE ERROR (inconclusive)"; all_ok=0
  elif echo "$out" | grep -qE "> ${method}\(.*\) FAILED"; then echo "$name -> detected"
  else echo "$name -> NOT DETECTED"; all_ok=0; fi
done
[ "$all_ok" = 1 ] && echo "all regressions detected" || { echo "some regressions NOT detected"; exit 1; }

#!/usr/bin/env bash
# ✦ Resyst VK — r13i private eval (PM12): 0.12.1's context offers vs 0.13.0's with the pair memory learned through the
# PRODUCT path, on a history of messages. Aggregates only (counts, rates, Wilson 95 % CIs, exact McNemar, the tau sweep,
# the cold-start size curve): no message text is printed or written; nothing is written next to the input; Gradle runs
# --offline (no network).
#
# Usage: scripts/r13i_private_eval.sh /path/messages.jsonl [/path/aggregate.json]
#   messages.jsonl: one {"ts": <seconds>, "source": "phone"|"desktop"|..., "text": "..."} per line
#   default output: app/build/r13i-private-eval/aggregate.json
# Environment:
#   VK_GRADLE_PREFIX  optional command prefix for the Gradle call (e.g. a memory-capped scope)
#
# The entry point is the JVM test com.resyst.vk.core.R13iPrivateEvalTest.privateEval (R13iPrivateEval.kt):
# r13g's hygiene and chronological splits (split80, split65), r13g's slip generator (seeds 1-3; seed 0 = as sent),
# learning by typing the learn split key by key through the shipped engine with PairLearner attached.
set -euo pipefail
if [ $# -lt 1 ] || [ ! -f "$1" ]; then
  echo "usage: $0 /path/messages.jsonl [/path/aggregate.json]" >&2
  exit 2
fi
in="$(realpath "$1")"
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$(realpath -m "${2:-$root/app/build/r13i-private-eval/aggregate.json}")"
mkdir -p "$(dirname "$out")"
cd "$root"
# --rerun: the input is an environment variable, which Gradle's up-to-date check does not see
# shellcheck disable=SC2086
VK_PRIVATE_EVAL="$in" VK_PRIVATE_EVAL_OUT="$out" ${VK_GRADLE_PREFIX:-} ./gradlew --no-daemon --offline -q \
  testDebugUnitTest --tests 'com.resyst.vk.core.R13iPrivateEvalTest.privateEval' --rerun
echo "✦ aggregates: $out"
cat "$out"

#!/bin/sh
# Validate scoring_runner.pyc end-to-end: define a real BaseMetric subclass
# (exercising the import-patching path) and assert the returned ScoreResult.
# Fails non-zero if the expected score isn't emitted.
set -eu

RUNNER="${1:-./scoring_runner.pyc}"

CODE="from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
class T(BaseMetric):
    def score(self, output, **ignored):
        return ScoreResult(name='selftest', value=1.0)"

python "$RUNNER" "$CODE" '{"output": "ok"}' | grep -q '"value": 1.0'

# A failure must name its cause, and must not leak the runner's own frames. This
# one raises while binding the call, so it has no user frame and the shortest
# possible traceback -- the case a fixed-length slice used to discard entirely.
STRICT_CODE="from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
class T(BaseMetric):
    def score(self, output):
        return ScoreResult(name='selftest', value=1.0)"

OUT=$(python "$RUNNER" "$STRICT_CODE" '{"output": "ok", "metadata": "x"}' || true)
printf '%s' "$OUT" | grep -q "unexpected keyword argument 'metadata'"
printf '%s' "$OUT" | grep -qv scoring_runner || { echo "runner frame leaked into user error" >&2; exit 1; }

# The other branch through the same helper: a failure raised by exec(code) has the
# user's frame where the score path has the runner's, so the two need opposite
# outcomes from one skip count. Over-skipping here would drop the user's location.
BROKEN_CODE="class T("

OUT=$(python "$RUNNER" "$BROKEN_CODE" '{"output": "ok"}' || true)
printf '%s' "$OUT" | grep -q "invalid Python code"
printf '%s' "$OUT" | grep -q "SyntaxError"
printf '%s' "$OUT" | grep -q '<string>'

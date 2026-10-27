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
if printf '%s' "$OUT" | grep -q scoring_runner; then
  echo "runner frame leaked into user error" >&2
  exit 1
fi

# A compile-time failure has no frames at all, so its location comes from the
# exception rather than from the walk. This pins that the location survives -- not
# the skip count, which cannot affect an empty frame list.
BROKEN_CODE="class T("

OUT=$(python "$RUNNER" "$BROKEN_CODE" '{"output": "ok"}' || true)
printf '%s' "$OUT" | grep -q "invalid Python code"
printf '%s' "$OUT" | grep -q "SyntaxError"
printf '%s' "$OUT" | grep -q '<string>'

# A failure raised while exec() runs the module body does have a user frame, which
# is what pins the skip count on this branch: over-skipping drops it.
RAISING_CODE="raise ValueError('boom')"

OUT=$(python "$RUNNER" "$RAISING_CODE" '{"output": "ok"}' || true)
printf '%s' "$OUT" | grep -q "invalid Python code"
printf '%s' "$OUT" | grep -q "ValueError: boom"
printf '%s' "$OUT" | grep -q 'line 1, in <module>'

# The report is formatted from an exception object the metric defined, inside the
# handler for that metric's failure, so nothing on it may make formatting raise --
# that would lose the message and turn the metric's own error into a server error.
HOSTILE_CODE="from opik.evaluation.metrics import BaseMetric
class Hostile(Exception):
    exceptions = 42
class T(BaseMetric):
    def score(self, output):
        raise Hostile('my own message')"

OUT=$(python "$RUNNER" "$HOSTILE_CODE" '{"output": "ok"}' || true)
printf '%s' "$OUT" | grep -q "Hostile: my own message"

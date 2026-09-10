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

# A failure must name its cause. This one raises while binding the call, so it
# has no user frame and the shortest possible traceback -- the case a fixed-length
# slice used to discard entirely, reporting an empty cause.
STRICT_CODE="from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
class T(BaseMetric):
    def score(self, output):
        return ScoreResult(name='selftest', value=1.0)"

python "$RUNNER" "$STRICT_CODE" '{"output": "ok", "metadata": "x"}' \
  | grep -q "unexpected keyword argument 'metadata'"

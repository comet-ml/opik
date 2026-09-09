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

# A required parameter the data has no key for must still score. The rule maps
# each score() parameter to a trace field, and a field the entity never logged
# arrives with its key absent -- which used to miss the argument and raise.
REQUIRED_CODE="from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
class T(BaseMetric):
    def score(self, output, metadata, **ignored):
        return ScoreResult(name='selftest', value=1.0, reason='metadata=%r' % (metadata,))"

python "$RUNNER" "$REQUIRED_CODE" '{"output": "ok"}' | grep -q '"reason": "metadata=None"'

# The counterpart: None is a value, so filling it must not displace a default.
DEFAULT_CODE="from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
class T(BaseMetric):
    def score(self, output, threshold=0.5, **ignored):
        return ScoreResult(name='selftest', value=threshold)"

python "$RUNNER" "$DEFAULT_CODE" '{"output": "ok"}' | grep -q '"value": 0.5'

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

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

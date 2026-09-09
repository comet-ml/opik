/**
 * Python source for user-defined-metric rules used by online-evaluation specs.
 *
 * These are plain string builders, not fixtures: they own no state and need no
 * per-test setup or teardown, so there is nothing for a fixture's `use()` to
 * bracket. They live here rather than in one spec because more than one spec
 * needs the same metric.
 *
 * Two constraints apply to every snippet, and both are easy to break by
 * accident:
 *
 *   - **No extra `BaseMetric` imports.** The python evaluator's
 *     `get_metric_class` walks module classes alphabetically and takes the first
 *     `BaseMetric` subclass, so importing one of opik's own heuristics (Equals,
 *     Moderation, …) can shadow the class defined here.
 *   - **The `ScoreResult` name is what lands on the trace**, not the rule name —
 *     the engine uses the score-result name verbatim. Hence `scoreName` is
 *     interpolated into the source rather than left to the caller.
 */

/**
 * A metric that returns a constant 1.0 for whatever it is handed.
 *
 * For specs asking *whether* an evaluation happened rather than what it
 * concluded: a 0.0 would then mean "ran on unexpected input", which is a
 * different failure from "was never evaluated".
 *
 * `scoreArgs` declares `score()`'s parameters. The default (`output`) suits a
 * rule mapping one whole section. Pass explicit names when the rule maps
 * sub-paths: an unresolvable sub-path is dropped from the argument map by
 * `OnlineScoringEngine.toReplacements`, so every parameter must have a default
 * or the call raises a TypeError that reads exactly like the bug under test.
 */
export function buildConstantScoreMetric(
  scoreName: string,
  scoreArgs: readonly string[] = ['output'],
): string {
  const params = scoreArgs.map((a) => `        ${a}: Any = None,`).join('\n');
  return `from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}

class ConstantScore(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(
        self,
${params}
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        return score_result.ScoreResult(value=1.0, name=self.name)`;
}

/**
 * A thread-scope metric: a constant score for any conversation, except one that
 * carries `poisonMarker` anywhere in it, which raises instead.
 *
 * Two choices here are load-bearing and both are easy to get wrong.
 *
 * **Plain `BaseMetric`, not `ConversationThreadMetric`.** For a `trace_thread`
 * payload the runner calls `metric.score(data)` with the whole conversation as
 * the first POSITIONAL argument, so the thread contract is a signature, not a
 * base class. Subclassing `ConversationThreadMetric` would import a submodule
 * the sandbox runner does not stub, which makes it drop its lightweight
 * `BaseMetric` and load the real `opik` package — after which the user class no
 * longer subclasses the `BaseMetric` the runner is still holding, and
 * `get_metric_class` reports "no BaseMetric subclass" rather than scoring.
 * Importing it also risks shadowing this class, per the header note above.
 *
 * **The marker is matched over the serialized conversation**, not over a
 * hand-walked `message["content"]`. The engine sends `{role, content}` for a
 * plain thread and nests a whole span tree under the assistant entries when it
 * enriches one, so a metric that indexed into a fixed shape would stop raising —
 * silently — the day a thread got big enough to change shape.
 *
 * The raise is a plain `ValueError`: what the spec asserts is that the failure
 * is confined to its own thread, not how the evaluator classifies it.
 */
export function buildThreadScoreMetric(
  scoreName: string,
  scoreValue: number,
  poisonMarker: string,
): string {
  return `import json
from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}
SCORE_VALUE = ${JSON.stringify(scoreValue)}
POISON_MARKER = ${JSON.stringify(poisonMarker)}

class ThreadConstantScore(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(
        self,
        conversation: Any = None,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        if POISON_MARKER in json.dumps(conversation, default=str):
            raise ValueError("refusing to score a conversation carrying " + POISON_MARKER)
        return score_result.ScoreResult(value=SCORE_VALUE, name=self.name)`;
}

/**
 * A metric that exits 0 without ever printing its result line.
 *
 * `os._exit` is deliberate: it ends the interpreter immediately, so the runner's
 * own "print the ScoreResult" step never happens while the process still reports
 * success. That is the shape the evaluator used to mis-handle —
 * `parse_execution_result` indexed an empty output list, and the IndexError
 * surfaced as an opaque 500 the backend then retried.
 *
 * A metric that merely raised would not reproduce it: a non-zero exit code takes
 * a different branch.
 */
export function buildSilentMetric(scoreName: string): string {
  return `import os
from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}

class SilentMetric(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, output: Any = None, **ignored_kwargs: Any) -> score_result.ScoreResult:
        os._exit(0)`;
}

/**
 * A metric that exits 0 having printed something that is not the result JSON.
 *
 * The sibling branch of the same fix: exit code 0 whose LAST line does not parse
 * as JSON. `flush=True` matters — `os._exit` skips interpreter shutdown, so an
 * unflushed buffer would be discarded and this would degenerate into
 * `buildSilentMetric`, testing one branch twice.
 */
export function buildUnparseableMetric(scoreName: string): string {
  return `import os
from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}

class UnparseableMetric(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, output: Any = None, **ignored_kwargs: Any) -> score_result.ScoreResult:
        print("this line is not a score result", flush=True)
        os._exit(0)`;
}

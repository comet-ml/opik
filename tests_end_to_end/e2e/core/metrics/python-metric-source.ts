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
 * One `ScoreResult` for `buildScoreListMetric` to return.
 *
 * `value` is `number | null` rather than `number` on purpose. `ScoreResult.value`
 * is declared `float` in the SDK and nothing enforces it at runtime, so `None` is
 * a shape a real user metric can and does produce — and it is one of the two
 * reasons a score is unusable. Typing it away here would make that case
 * unreachable from a spec.
 */
export interface ScoreResultSpec {
  /** Lands on the trace verbatim. `''` produces the unnamed case, reported as `<unnamed>`. */
  name: string;
  /** `null` emits `ScoreResult(value=None, …)` — a score that cannot be stored. */
  value: number | null;
  /**
   * The metric's own admission that scoring did not complete. The SDK pairs it
   * with a placeholder `0.0`, so the score is storable but must not be stored:
   * that is what makes this distinct from `value: 0` and worth a spec.
   */
  scoringFailed?: boolean;
  reason?: string;
}

/**
 * A metric returning an arbitrary list of `ScoreResult`s, verbatim.
 *
 * Deliberately general where its siblings above are purpose-built: the
 * unusable-score behaviour is one rule shape crossed with several score lists
 * (mixed usable/failed, wholly failed, valueless, unnamed, a deliberate zero),
 * and a builder per combination would be five near-identical copies of the same
 * five lines. What varies between those cases is data, so it is passed as data.
 *
 * The list is embedded as a JSON string and parsed at run time rather than
 * inlined as a Python literal: `null`, `true` and `false` are valid JSON and not
 * valid Python, so a straight interpolation would produce a metric that fails to
 * import — for exactly the `value: null` case a spec most wants to seed.
 *
 * The runner accepts a bare `ScoreResult` or a list of them (`to_scores`), so a
 * one-element list is the same shape a single return would take. Always
 * returning a list keeps every case built here on one code path.
 */
export function buildScoreListMetric(scores: readonly ScoreResultSpec[]): string {
  // `JSON.stringify` renders NaN and ±Infinity as `null`, which is silently the
  // *other* unusable-score case this builder seeds. A spec meaning to pass a
  // non-finite value would get a valueless score, and its assertions about the
  // valueless path would pass for a reason its author never wrote down. Fail at
  // the boundary instead: no caller wants the substitution.
  for (const s of scores) {
    if (s.value !== null && !Number.isFinite(s.value)) {
      throw new Error(
        `buildScoreListMetric: score '${s.name}' has non-finite value ${s.value}; ` +
          'JSON encoding would turn it into null and seed the valueless case instead. ' +
          'Pass a finite number, or null to seed a valueless score deliberately.',
      );
    }
  }
  const payload = scores.map((s) => ({
    name: s.name,
    value: s.value,
    ...(s.scoringFailed === undefined ? {} : { scoring_failed: s.scoringFailed }),
    ...(s.reason === undefined ? {} : { reason: s.reason }),
  }));
  // Stringified twice: once to JSON, once into a quoted literal that is valid in
  // both languages, so the source below embeds it without any escaping of its own.
  const encoded = JSON.stringify(JSON.stringify(payload));
  return `import json
from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORES = json.loads(${encoded})

class ScoreListMetric(base_metric.BaseMetric):
    def __init__(self, name: str = "score_list"):
        self.name = name

    def score(self, output: Any = None, **ignored_kwargs: Any) -> Any:
        return [score_result.ScoreResult(**spec) for spec in SCORES]`;
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

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

/**
 * A metric that reports, in its own score, what `metadata` was bound to.
 *
 * `metadata` is declared as a REQUIRED positional with no default, which is the
 * PRE-FIX shape of the dialog's shipped template — and therefore the shape every
 * rule saved before OPIK-8292 still holds. The frontend half of that fix
 * (`metadata: Optional[str] = None`) does nothing for those rules; only
 * `bindDeclaredArguments` binding the declared argument keeps them callable, so
 * this signature is the one that pins the backend half on its own.
 *
 * The bound value is encoded into the SCORE rather than merely scoring, because
 * "scored at all" cannot tell a bound `None` from a value the entity really
 * logged — and confusing those two is exactly the failure the fix's docstring
 * warns about:
 *
 *   - `1.0` — `metadata` arrived as `None`, i.e. the argument was bound for an
 *     entity that carried no metadata.
 *   - `2.0` — `metadata` arrived carrying something.
 *
 * `reason` carries the runtime type name, which is a second, independent fact:
 * these arguments reach the metric as JSON STRINGS, not dicts (the mis-annotation
 * the same fix corrected), so a present metadata must report `str` and never
 * `dict`.
 */
export function buildMetadataBindingProbeMetric(scoreName: string): string {
  return `from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}

class MetadataBindingProbe(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, input, output, metadata: dict, **ignored_kwargs: Any) -> score_result.ScoreResult:
        value = 1.0 if metadata is None else 2.0
        return score_result.ScoreResult(
            value=value,
            name=self.name,
            reason="metadata_type=" + type(metadata).__name__,
        )`;
}

/**
 * A metric declaring `spans` alongside an absent `metadata`, scoring the span
 * count.
 *
 * `spans` is the one declared argument `bindDeclaredArguments` deliberately does
 * NOT bind: it is injected as a typed list by `toReplacements(Map, Trace, List)`
 * rather than resolved from an extraction path, so binding it to `null` for want
 * of a path would break every rule that declares it. Scoring `len(spans)` is what
 * makes that observable — a nulled `spans` raises instead of scoring, and a
 * `spans` degraded to a string would still have a length.
 *
 * Both behaviours are asserted from one call on purpose: the same invocation has
 * to keep the injected list AND bind the absent metadata to `None`, which is
 * where a fix for one could regress the other.
 */
export function buildSpansAndMetadataMetric(scoreName: string): string {
  return `from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}

class SpansAndMetadataMetric(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, output, spans, metadata: dict, **ignored_kwargs: Any) -> score_result.ScoreResult:
        return score_result.ScoreResult(
            value=float(len(spans)),
            name=self.name,
            reason="spans_type=" + type(spans).__name__ + " metadata_type=" + type(metadata).__name__,
        )`;
}

/**
 * A metric declaring a parameter the rule's variable mapping does not name.
 *
 * The failure lands at CALL-SITE BINDING — `metric.score(**data)` raises before
 * the interpreter enters `score()` — so the traceback holds no user frame at all.
 * That is the zero-frame case `scoring_runner`'s old fixed `splitlines()[3:]`
 * slice emptied: the runtime image ships `scoring_runner.pyc` only, built with
 * `PYTHONNODEBUGRANGES=1`, so neither a source nor a caret line pads the three
 * lines the slice removed and the reported cause came back blank.
 *
 * `bindDeclaredArguments` does not paper over it: it binds the keys the rule's
 * `arguments` map DECLARES, and this parameter is deliberately not one of them.
 */
export function buildUnboundArgumentMetric(scoreName: string, unboundArgument: string): string {
  return `from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}

class UnboundArgumentMetric(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, output, ${unboundArgument}, **ignored_kwargs: Any) -> score_result.ScoreResult:
        return score_result.ScoreResult(value=1.0, name=self.name)`;
}

/**
 * A metric that raises inside `score()`.
 *
 * The contrasting case to `buildUnboundArgumentMetric`: here a user frame DOES
 * exist, so the reported cause must keep both the exception line and the
 * `File "<string>"` frame that names where in the user's own code it happened.
 * The old slice discarded that frame even when it left the message intact.
 */
export function buildRaisingMetric(scoreName: string, message: string): string {
  return `from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}
MESSAGE = ${JSON.stringify(message)}

class RaisingMetric(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, output: Any = None, **ignored_kwargs: Any) -> score_result.ScoreResult:
        raise ValueError(MESSAGE)`;
}

/**
 * A metric that raises while the module itself is being executed.
 *
 * The OTHER call site of `user_facing_stacktrace` — the runner's `exec()` of the
 * submitted source, which fails before any class exists to instantiate. It is
 * reported under a different prefix from a `score()` failure ("Field 'code'
 * contains invalid Python code:"), so a fix that only covered the scoring call
 * site would leave this one blank.
 */
export function buildModuleLevelRaisingMetric(scoreName: string, message: string): string {
  return `from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}
MESSAGE = ${JSON.stringify(message)}

raise RuntimeError(MESSAGE)

class ModuleLevelRaisingMetric(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, output: Any = None, **ignored_kwargs: Any) -> score_result.ScoreResult:
        return score_result.ScoreResult(value=1.0, name=self.name)`;
}

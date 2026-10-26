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
 * One score a metric is to return, as `buildScoreResultMetric` renders it.
 *
 * `value: null` and `scoringFailed: true` are the two shapes the backend drops
 * for different stated reasons, and they are deliberately expressible
 * independently — a caller has to be able to build the flagged-with-a-0.0 score
 * the SDK actually emits, which is storable-looking and must still be dropped.
 */
export interface PythonScoreSpec {
  /**
   * The name this score lands under. It is the ScoreResult's own name, not the
   * rule's — the engine uses it verbatim — so two scores from one metric need
   * two names or one silently overwrites the other.
   */
  name: string;
  /** `null` renders `value=None`: the "metric returned no value" drop. */
  value: number | null;
  /** Renders `scoring_failed=True`: the "metric reported the scoring as failed" drop. */
  scoringFailed?: boolean;
}

/** A TS value as the Python literal it has to become inside the metric source. */
function toPythonLiteral(value: number | string | boolean | null): string {
  if (value === null) return 'None';
  if (typeof value === 'boolean') return value ? 'True' : 'False';
  // JSON.stringify renders NaN and ±Infinity as `null`, which would silently
  // emit `value=null` — not Python's `None` but a name Python cannot resolve,
  // so the metric would die on a NameError that looks nothing like the caller's
  // mistake. Refuse here, where the bad value is still attributable.
  if (typeof value === 'number' && !Number.isFinite(value)) {
    throw new Error(
      `buildScoreResultMetric: ${value} has no Python literal form. ` +
        'Use a finite number, or `value: null` for the "metric returned no value" drop.',
    );
  }
  // JSON's string and number grammars are both Python literal grammars for the
  // ASCII names and finite values these specs use.
  return JSON.stringify(value);
}

/**
 * A metric that returns exactly the scores it is given — including the ones the
 * backend is supposed to refuse to store.
 *
 * The three other builders here answer "did the evaluator run at all"; this one
 * answers "which of a run's scores survived", which is a different question and
 * needs a metric whose output is unusable on purpose. Every drop path is one
 * `ScoreResult` field away from a storable score, so the shapes have to be
 * constructed rather than provoked.
 *
 * **A single spec returns a bare `ScoreResult`, several return a list**, because
 * the two take different paths and only one of them can produce a partial
 * result: a single unusable score makes the whole response unusable and the
 * python evaluator answers 400, while a list holding one usable score is passed
 * through for the backend to split. Wrapping a single score in a list would
 * quietly test the list path twice.
 */
export function buildScoreResultMetric(
  metricName: string,
  scores: readonly PythonScoreSpec[],
): string {
  const construct = (spec: PythonScoreSpec) =>
    `score_result.ScoreResult(name=${toPythonLiteral(spec.name)}, ` +
    `value=${toPythonLiteral(spec.value)}, ` +
    `scoring_failed=${toPythonLiteral(spec.scoringFailed ?? false)})`;

  const body =
    scores.length === 1
      ? `return ${construct(scores[0])}`
      : `return [\n${scores.map((s) => `            ${construct(s)},`).join('\n')}\n        ]`;

  return `from typing import Any
from opik.evaluation.metrics import base_metric, score_result

METRIC_NAME = ${JSON.stringify(metricName)}

class ReportedScores(base_metric.BaseMetric):
    def __init__(self, name: str = METRIC_NAME):
        self.name = name

    def score(self, output: Any = None, **ignored_kwargs: Any) -> Any:
        ${body}`;
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
 * A metric whose `score()` declares a parameter with NO DEFAULT.
 *
 * Every other builder here renders each parameter as `: Any = None`, which is
 * deliberate — an unresolvable sub-path is dropped from the argument map by
 * `OnlineScoringEngine.toReplacements`, so a defaulted parameter keeps the call
 * working. That is also why the estate structurally cannot express the shape
 * OPIK-8292 is about: a REQUIRED parameter whose mapped field is missing from
 * the trace. Before the fix the argument was simply dropped and the call died
 * on a TypeError, so the trace carried no score at all; after it, the parameter
 * is bound to `None`.
 *
 * The metric reports WHICH of the two happened, in the score itself: `absentValue`
 * when the parameter arrived as `None` and `presentValue` otherwise, with the
 * repr of what it received as the reason. A spec can then tell "filled with
 * None" from "filled with the real value" from "never scored" — three outcomes
 * a bare "a score exists" check collapses into one.
 *
 * `output` is required too, and intentionally: a signature mixing a defaulted
 * and an undefaulted parameter would leave it ambiguous which one the engine's
 * behaviour was being read from.
 */
export function buildRequiredParamMetric(
  scoreName: string,
  requiredParam: string,
  absentValue: number,
  presentValue: number,
): string {
  return `from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}

class RequiredParamScore(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, output, ${requiredParam}, **ignored_kwargs: Any) -> score_result.ScoreResult:
        value = ${JSON.stringify(absentValue)} if ${requiredParam} is None else ${JSON.stringify(presentValue)}
        return score_result.ScoreResult(
            value=value,
            name=self.name,
            reason="${requiredParam}=" + repr(${requiredParam}),
        )`;
}

/**
 * The third-party distributions a dependency-set fingerprint reports.
 *
 * Diagnostics, not assertions — see {@link buildDependencyImportMetric}. The
 * set is chosen to be *discriminating*: `opik`, `litellm` and `Flask` are
 * pinned to different versions in the sandbox-executor image than in the
 * python-backend, so a reader of a failure can tell from the fingerprint alone
 * which of the two interpreters ran the metric. The rest (`aiohttp`, `yarl`,
 * `urllib3`, `idna`) are the pin set the 2.2.107 build conflict was about.
 */
const FINGERPRINT_DISTRIBUTIONS = [
  'opik',
  'litellm',
  'aiohttp',
  'yarl',
  'urllib3',
  'idna',
] as const;

/**
 * A metric that imports heavy third-party packages AT MODULE SCOPE and then
 * returns a constant score whose `reason` fingerprints the runtime that ran it.
 *
 * Every other builder here imports nothing but `opik`'s own stubs, so between
 * them they answer "did the evaluator run" and "what did it conclude" while
 * saying nothing about whether the interpreter it ran in can import its own
 * dependency set. That gap is not academic: the executor image is loaded from a
 * tarball baked into the python-backend at build time, and an `ImportError` from
 * the aiohttp/yarl/litellm set surfaces as NO SCORE AT ALL — one fewer row on
 * the trace panel — never as an error the user sees. It is exactly what the
 * 2.2.107 sandbox-executor build failure would have shipped.
 *
 * Three things about the generated source are load-bearing:
 *
 * **The stub import comes first, and the class is defined before the heavy
 * imports.** `scoring_runner.py` registers lightweight `opik.*` stubs in
 * `sys.modules` and holds its own reference to the stub `BaseMetric`. Any
 * import that reaches the real `opik` package evicts those stubs, after which a
 * class defined against the real `BaseMetric` is not a subclass of the one the
 * runner is still holding and `get_metric_class` reports "no BaseMetric
 * subclass" instead of scoring. Binding the base class from the stubs on line 1
 * makes that reordering impossible however the heavy packages behave — a class's
 * MRO is fixed at definition, so a later eviction cannot retroactively change it.
 *
 * **The imports sit at module scope, not inside `score()`.** The runner
 * `exec()`s the whole module before it looks for a metric class, so a broken pin
 * aborts there and is reported as invalid code — the same observable shape (no
 * score) as a missing rule. Hiding the imports inside `score()` would still
 * fail, but as a scoring error with a traceback, which is a different and
 * strictly easier failure to spot.
 *
 * **The fingerprint is read from distribution metadata, never by importing.**
 * `importlib.metadata.version('opik')` reads the installed `.dist-info` without
 * executing the package, so the discriminator can name the real `opik` version
 * without evicting the stubs the paragraph above depends on. `Flask` is probed
 * with `find_spec`, which likewise locates without importing.
 *
 * The fingerprint is a DIAGNOSTIC and a caller must not assert version numbers
 * on it — every one of them moves on a routine pin bump, which would turn this
 * into a spec that fails for the one reason it is not about. Assert that the
 * score arrived; attach the fingerprint so a human reading a future failure can
 * tell which image produced it.
 *
 * @param imports Modules imported at module scope — the thing under test.
 *   Expensive by design, and therefore bounded: the python-backend's
 *   `PYTHON_CODE_EXECUTOR_EXEC_TIMEOUT_IN_SECS` defaults to 3s and the whole
 *   module import happens inside it, so a caller adding to this list is
 *   spending that budget.
 *
 *   Two packages in the image are known NOT to fit and must not be passed here.
 *   `litellm`: measured on staging, a module-scope import of it yields no score
 *   at all and a rule log that stops after "Sending … to Python evaluator" with
 *   no error row — so a spec importing it asserts the timeout, not
 *   importability. The real `opik` package: its init costs ~2.5s by
 *   `scoring_runner.py`'s own measurement, and importing it would also evict
 *   the stubs the paragraph above depends on. Both versions are still reported
 *   in the fingerprint, which is free because it reads distribution metadata.
 */
export function buildDependencyImportMetric(
  scoreName: string,
  scoreValue: number,
  imports: readonly string[],
): string {
  if (imports.length === 0) {
    throw new Error(
      'buildDependencyImportMetric: an empty import list makes this ' +
        'buildConstantScoreMetric with extra steps — pass the modules whose ' +
        'importability is under test, or use that builder instead.',
    );
  }
  // A dotted or aliased name would compile but put something other than a
  // module in the namespace, and `import a.b` binds `a` — which silently
  // changes what is being proven importable. Refuse here, where the caller's
  // mistake is still attributable, rather than emit source that tests the
  // wrong thing.
  const malformed = imports.filter((m) => !/^[a-z_][a-z0-9_]*$/i.test(m));
  if (malformed.length > 0) {
    throw new Error(
      `buildDependencyImportMetric: ${malformed.join(', ')} is not a plain ` +
        'top-level module name. Only top-level modules can be asserted importable here.',
    );
  }

  const heavyImports = imports.map((m) => `import ${m}`).join('\n');
  const reported = FINGERPRINT_DISTRIBUTIONS.map(
    (d) => `    ${JSON.stringify(d)} + "=" + _dist_version(${JSON.stringify(d)}),`,
  ).join('\n');

  // `typing.Any` is imported up here with the stubs rather than alongside the
  // stdlib helpers below, because a parameter annotation is evaluated when
  // `def` runs: `Any` has to exist before the class, not merely before the
  // first call.
  return `from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}
SCORE_VALUE = ${JSON.stringify(scoreValue)}
IMPORTED = ${JSON.stringify(imports.join(','))}

class DependencySetScore(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(
        self,
        output: Any = None,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        return score_result.ScoreResult(
            value=SCORE_VALUE,
            name=self.name,
            reason=FINGERPRINT,
        )

# Module scope, and after the class above: a broken pin in this set aborts the
# module here, so the rule produces no score at all rather than a scoring error.
${heavyImports}

import importlib.metadata
import importlib.util
import os
import platform


def _dist_version(dist: str) -> str:
    """The installed version of a distribution, read without importing it."""
    try:
        return importlib.metadata.version(dist)
    except Exception:
        return "absent"


FINGERPRINT = " ".join([
    "py" + platform.python_version(),
${reported}
    # Installed in the python-backend and absent from the sandbox executor, so
    # this single field says which of the two ran the metric.
    "flask=" + ("present" if importlib.util.find_spec("flask") is not None else "absent"),
    "dockerenv=" + str(os.path.exists("/.dockerenv")),
    "imported=" + IMPORTED,
])`;
}

/**
 * A metric that raises inside `score()`.
 *
 * The straightforward half of what an ERROR row has to say: a real user frame
 * exists, so the traceback has something to name. The spec pins both the
 * exception type and its message, and the presence of the `File "<string>"`
 * frame the metric source compiles to.
 */
export function buildRaisingMetric(scoreName: string, message: string): string {
  return `from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}

class RaisingMetric(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, output: Any = None, **ignored_kwargs: Any) -> score_result.ScoreResult:
        raise ValueError(${JSON.stringify(message)})`;
}

/**
 * A metric whose `score()` accepts NO `**kwargs`, so an unexpected mapped
 * argument fails while BINDING the call.
 *
 * The valuable half, and the one the old fixed-slice traceback formatter
 * returned an empty string for: a bind failure happens before any user code
 * runs, so the traceback has no user frame at all — the shortest traceback
 * there is. An ERROR row that says only "can't be evaluated: " tells a user
 * nothing, and is indistinguishable at a glance from a row that named its
 * cause.
 *
 * Omitting `**ignored_kwargs` is the whole mechanism; every other builder here
 * accepts it precisely so that extra arguments are tolerated.
 */
export function buildBindFailureMetric(scoreName: string): string {
  return `from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}

class BinderScore(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, output: Any = None) -> score_result.ScoreResult:
        return score_result.ScoreResult(value=1.0, name=self.name)`;
}

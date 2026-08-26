import re
from typing import Any, Callable, Optional, Sequence, Tuple, Union

try:
    import nltk  # type: ignore
    from nltk.corpus import wordnet  # type: ignore
except ImportError:  # pragma: no cover - optional dependency
    nltk = None
    wordnet = None

from opik.exceptions import MetricComputationError
from opik.evaluation.metrics import base_metric, score_result

# NLTK 3.6.5 switched `meteor_score` to pre-tokenized input; 3.6.4 and earlier
# expect untokenized strings. Supporting both would mean branching on a release
# from 2021, so the default backend requires the modern API and says so clearly.
#
# Upstream change: https://github.com/nltk/nltk/pull/2822 ("Accept pre-tokenized
# references & hypothesis for METEOR calculation"), first shipped in 3.6.5 —
# see https://github.com/nltk/nltk/blob/3.6.5/ChangeLog ("Version 3.6.5
# 2021-10-11 ... METEOR evaluation now requires pre-tokenized input"). Compare
# https://github.com/nltk/nltk/blob/3.6.4/nltk/translate/meteor_score.py#L343
# with https://github.com/nltk/nltk/blob/3.6.5/nltk/translate/meteor_score.py#L347
# for the signature change.
MINIMUM_NLTK_VERSION = "3.6.5"
MINIMUM_NLTK_VERSION_INFO = (3, 6, 5)

# NLTK reports two-component versions for several real releases ("3.5", "3.4",
# "3.3") and used prerelease suffixes in the past ("3.0a3"), so the version
# string cannot be fed to a strict SemVer parser — it would raise ValueError on
# exactly the old installs this guard exists to reject. Read the leading numeric
# components instead.
_VERSION_RE = re.compile(r"^\s*v?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?P<suffix>\S*)")
_PRERELEASE_RE = re.compile(r"^[-._]?(a|b|c|rc|alpha|beta|dev|pre)", re.IGNORECASE)


def _is_below_minimum_nltk_version(version: str) -> bool:
    """Whether `version` is known to predate `MINIMUM_NLTK_VERSION`.

    Version strings that cannot be read at all — such as the ``"unknown (...)"``
    fallback a broken install can report — return `False`. Refusing to build the
    metric because a version string was unparseable would be worse than letting
    NLTK speak for itself, and `_scorer` turns the resulting `TypeError` into an
    actionable error anyway.
    """
    match = _VERSION_RE.match(version)
    if match is None:
        return False

    parsed: Tuple[int, ...] = tuple(
        int(part) if part else 0 for part in match.group(1, 2, 3)
    )
    if parsed == MINIMUM_NLTK_VERSION_INFO:
        # A prerelease of the minimum version ("3.6.5rc1") predates its API;
        # a post-release or local version ("3.6.5.post1", "3.6.5+local") does not.
        return _PRERELEASE_RE.match(match.group("suffix")) is not None
    return parsed < MINIMUM_NLTK_VERSION_INFO


try:
    from nltk.translate import meteor_score as nltk_meteor_score
except ImportError:  # pragma: no cover - optional dependency
    nltk_meteor_score = None


MeteorFn = Callable[[Sequence[str], str], float]


class METEOR(base_metric.BaseMetric):
    """Computes the METEOR score between output and reference text.

    This implementation wraps ``nltk.translate.meteor_score.meteor_score`` while
    allowing a custom scoring function to be injected (useful for testing).

    References:
      - Banerjee & Lavie, "METEOR: An Automatic Metric for MT Evaluation with Improved
        Correlation with Human Judgments" (ACL Workshop 2005)
        https://aclanthology.org/W05-0909/
      - Hugging Face Evaluate: METEOR metric overview
        https://huggingface.co/spaces/evaluate-metric/meteor

    Args:
        meteor_fn: Optional callable ``(references, hypothesis) -> float`` that
            receives **untokenized** text: a sequence of reference strings and a
            single hypothesis string. Note this deliberately differs from
            ``nltk.translate.meteor_score.meteor_score``, which requires
            pre-tokenized input — passing that function in directly will not
            work. When omitted, NLTK is used through an adapter that tokenizes
            on your behalf.
        alpha: Precision weight.
        beta: Penalty exponent.
        gamma: Fragmentation penalty weight.
        name: Optional metric name.
        track: Whether Opik should track the metric automatically.
        project_name: Optional project name used when tracking.
    """

    def __init__(
        self,
        meteor_fn: Optional[MeteorFn] = None,
        alpha: float = 0.9,
        beta: float = 3.0,
        gamma: float = 0.5,
        name: str = "meteor_metric",
        track: bool = True,
        project_name: Optional[str] = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)

        if meteor_fn is not None:
            self._meteor_fn = meteor_fn
        else:
            if nltk_meteor_score is None:  # pragma: no cover - optional dependency
                raise ImportError(
                    "METEOR metric requires the optional 'nltk' package. Install via"
                    " `pip install nltk` or provide `meteor_fn`."
                )

            installed_version = getattr(nltk, "__version__", "")
            if nltk is not None and _is_below_minimum_nltk_version(installed_version):
                raise ImportError(
                    f"METEOR metric requires nltk >= {MINIMUM_NLTK_VERSION}, but "
                    f"{installed_version} is installed. Earlier versions expect "
                    "untokenized input. Upgrade via `pip install -U nltk` or supply "
                    "`meteor_fn`."
                )

            if nltk is not None and wordnet is not None:
                try:
                    wordnet.ensure_loaded()  # type: ignore[attr-defined]
                except (
                    LookupError
                ):  # pragma: no cover - download path relies on network access
                    try:
                        nltk.download("wordnet", quiet=True)
                        nltk.download("omw-1.4", quiet=True)
                        wordnet.ensure_loaded()  # type: ignore[attr-defined]
                    except Exception as download_error:
                        raise ImportError(
                            "METEOR metric requires the NLTK corpora 'wordnet' and 'omw-1.4'. "
                            "Install manually via `python -m nltk.downloader wordnet omw-1.4`."
                        ) from download_error

            def _scorer(references: Sequence[str], hypothesis: str) -> float:
                # NLTK's meteor_score expects pre-tokenized input: an iterable of
                # token lists for the references and a token list for the
                # hypothesis. Handing it raw strings raises TypeError, so tokenize
                # here (whitespace split, matching BLEU/GLEU) while keeping the
                # public `meteor_fn` contract string-based.
                tokenized_references = [reference.split() for reference in references]
                tokenized_hypothesis = hypothesis.split()
                try:
                    return float(
                        nltk_meteor_score.meteor_score(
                            tokenized_references,
                            tokenized_hypothesis,
                            alpha=alpha,
                            beta=beta,
                            gamma=gamma,
                        )
                    )
                except TypeError as error:
                    # Only reachable when the version guard could not read
                    # `nltk.__version__`: NLTK < 3.6.5 rejects the tokenized
                    # input built above. Keep NLTK's own message so a genuine
                    # type error is not misreported as a version problem.
                    raise MetricComputationError(
                        f"NLTK rejected the pre-tokenized METEOR input: {error}. "
                        f"This usually means nltk < {MINIMUM_NLTK_VERSION} is "
                        "installed, which expects untokenized strings. Upgrade via "
                        "`pip install -U nltk` or supply `meteor_fn`."
                    ) from error
                except LookupError as error:
                    raise MetricComputationError(
                        "NLTK resource requirement for METEOR not satisfied. "
                        "Download WordNet via `nltk.download('wordnet')`."
                    ) from error

            self._meteor_fn = _scorer

    def score(
        self,
        output: str,
        reference: Union[str, Sequence[str]],
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        if not output.strip():
            raise MetricComputationError("Candidate is empty (METEOR metric).")
        if isinstance(reference, str):
            references: Sequence[str] = [reference]
        else:
            references = list(reference)
        if not references or any(not ref.strip() for ref in references):
            raise MetricComputationError("Reference is empty (METEOR metric).")

        score = self._meteor_fn(references, output)
        return score_result.ScoreResult(
            value=float(score),
            name=self.name,
            reason=f"METEOR score: {float(score):.4f}",
        )

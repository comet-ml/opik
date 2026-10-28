"""Run the worked examples in the distribution metric docstrings.

#8383: three of these examples printed numbers the metrics never return, and
they could not fail -- the statement that prints the number carries
``# doctest: +SKIP``, and pytest is not configured with ``--doctest-modules``.

Two tests, because the number can be wrong in two directions.  The first
executes the example exactly as written, so the inputs, the constructor options
and the printed value all have to agree with each other.  The second recomputes
the same three calls from the definition, so a change that moves the
implementation and the docstring together still fails.
"""

import doctest
import math
import re
import textwrap
from collections import Counter
from typing import Callable, Dict, List, Type

import pytest

from opik.evaluation.metrics import JSDistance, JSDivergence, KLDivergence

SKIP_DIRECTIVE = re.compile(r"[ \t]*#[ \t]*doctest:[ \t]*\+SKIP")


def _example_block(metric_class: Type[object]) -> str:
    """The statements under the class docstring's ``Example:`` heading.

    The ``# doctest: +SKIP`` directives are dropped: running the example is the
    point of this file.
    """
    docstring = metric_class.__doc__ or ""
    lines = docstring.split("\n")
    headings = [i for i, line in enumerate(lines) if line.strip() == "Example:"]
    assert headings, f"{metric_class.__name__} docstring has no Example: block"

    indent = len(lines[headings[0]]) - len(lines[headings[0]].lstrip())
    block: List[str] = []
    for line in lines[headings[0] + 1 :]:
        if line.strip() and len(line) - len(line.lstrip()) <= indent:
            break
        block.append(line)

    source = SKIP_DIRECTIVE.sub("", textwrap.dedent("\n".join(block))).strip()
    assert source, f"{metric_class.__name__} Example: block is empty"
    return source


@pytest.mark.parametrize(
    "metric_class", [JSDivergence, JSDistance, KLDivergence], ids=lambda c: c.__name__
)
def test_distribution_metric_docstring_example__run_as_written__matches_printed_value(
    metric_class: Type[object],
) -> None:
    source = _example_block(metric_class) + "\n"
    parsed = doctest.DocTestParser().get_doctest(
        source, {}, metric_class.__name__, None, 0
    )

    assert any(example.want.strip() for example in parsed.examples), (
        f"{metric_class.__name__} example prints no expected value, so nothing is pinned"
    )

    # ``track`` stays at the docstring's default on purpose: the example is what a user
    # copies. The autouse ``fake_backend`` fixture in tests/unit/evaluation/conftest.py
    # routes the spans that produces into the in-memory emulator, so no real client or
    # streamer is built here.

    reported: List[str] = []
    runner = doctest.DocTestRunner()
    runner.run(parsed, out=reported.append)

    assert runner.tries == len(parsed.examples), (
        f"{metric_class.__name__}: {runner.tries} of {len(parsed.examples)} example "
        "statements ran, so one was skipped and pins nothing"
    )
    assert runner.failures == 0, "".join(reported)


def _distribution(text: str) -> Dict[str, float]:
    counts = Counter(text.lower().split())
    total = float(sum(counts.values()))
    return {token: count / total for token, count in counts.items()}


def _kl(
    p: Dict[str, float], q: Dict[str, float], log: Callable[[float], float]
) -> float:
    """KL divergence from p to q, defined only where q covers p's support.

    The implementation's additive smoothing is deliberately not mirrored here: an
    expectation copied from the code under test stops being an independent check.
    Where smoothing actually decides the answer -- a token present on one side
    only -- the definition gives no finite value, so that is a separate case.
    """
    uncovered = sorted(set(p) - set(q))
    assert not uncovered, f"KL is undefined: {uncovered} have no probability in q"
    return sum(
        value * log(value / q[token]) for token, value in p.items() if value > 0.0
    )


def _expected_value(metric_class: Type[object], output: str, reference: str) -> float:
    """The documented quantity, computed from its definition instead of scipy."""
    p = _distribution(output)
    q = _distribution(reference)

    if metric_class is KLDivergence:
        # The example passes ``direction="avg"``, which is the symmetrised KL in
        # nats -- the implementation's default log base.
        return (_kl(p, q, math.log) + _kl(q, p, math.log)) / 2

    midpoint = {
        token: (p.get(token, 0.0) + q.get(token, 0.0)) / 2
        for token in p.keys() | q.keys()
    }
    divergence = (_kl(p, midpoint, math.log2) + _kl(q, midpoint, math.log2)) / 2

    if metric_class is JSDistance:
        # Named "distance" but advertises the divergence; the square root of
        # this value is what it reports as ``metadata["distance"]``.
        return divergence
    if metric_class is JSDivergence:
        return 1.0 - divergence
    raise AssertionError(f"no expectation defined for {metric_class.__name__}")


DOCUMENTED_CASES = [
    pytest.param(JSDivergence, {}, "cat cat sat", "cat sat on mat", id="js-divergence"),
    pytest.param(JSDistance, {}, "a a b", "a b b", id="js-distance"),
    pytest.param(
        KLDivergence,
        {"direction": "avg"},
        "hello hello world",
        "hello world",
        id="kl-divergence",
    ),
]


@pytest.mark.parametrize(
    "metric_class,constructor_kwargs,output,reference", DOCUMENTED_CASES
)
def test_distribution_metric_score__documented_case__matches_the_definition(
    metric_class: Type[object],
    constructor_kwargs: Dict[str, object],
    output: str,
    reference: str,
) -> None:
    """Same call as the docstring example, checked against the definition.

    Uses the default whitespace tokenizer and default log bases, which is what
    the examples in ``distribution_metrics.py`` advertise.
    """
    result = metric_class(track=False, **constructor_kwargs).score(
        output=output, reference=reference
    )

    assert result.value == pytest.approx(
        _expected_value(metric_class, output, reference), abs=1e-9
    ), (
        f"{metric_class.__name__} returned {result.value} for {output!r} vs {reference!r}"
    )

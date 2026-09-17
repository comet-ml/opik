"""Keep the worked examples in the distribution metric docstrings honest.

Every example in ``distribution_metrics.py`` is marked ``# doctest: +SKIP`` and
pytest is not configured with ``--doctest-modules``, so nothing in CI compares
the printed numbers with what the metrics actually return.
"""

import re
from typing import Any, Tuple, Type

import pytest

from opik.evaluation.metrics import JSDistance, JSDivergence, KLDivergence

EXAMPLE_RE = re.compile(
    r">>>\s*round\(result\.(\w+),\s*(\d+)\)(?:\s*#[^\n]*)?\n\s*([0-9.]+)\s*\n"
)

CASES = [
    pytest.param(
        JSDivergence,
        {},
        {"output": "cat cat sat", "reference": "cat sat on mat"},
        id="js-divergence",
    ),
    pytest.param(
        JSDistance,
        {},
        {"output": "a a b", "reference": "a b b"},
        id="js-distance",
    ),
    pytest.param(
        KLDivergence,
        {"direction": "avg"},
        {"output": "hello hello world", "reference": "hello world"},
        id="kl-divergence",
    ),
]


def _documented_example(metric_class: Type[Any]) -> Tuple[int, float]:
    """The rounding digits and the value printed under the ``>>> round(...)`` line."""
    match = EXAMPLE_RE.search(metric_class.__doc__ or "")

    assert match is not None, f"{metric_class.__name__} has no parseable worked example"
    attribute, ndigits, printed = match.groups()

    # The examples read `result.value`; if that ever changes, this test would be
    # checking a different quantity than the one the docstring advertises.
    assert attribute == "value", f"{metric_class.__name__} example reads {attribute!r}"

    return int(ndigits), float(printed)


@pytest.mark.parametrize("metric_class,constructor_kwargs,score_kwargs", CASES)
def test_docstring_example_reports_the_value_the_metric_returns(
    metric_class: Type[Any],
    constructor_kwargs: dict,
    score_kwargs: dict,
) -> None:
    ndigits, documented = _documented_example(metric_class)

    result = metric_class(track=False, **constructor_kwargs).score(**score_kwargs)

    assert round(result.value, ndigits) == pytest.approx(documented), (
        f"{metric_class.__name__} docstring advertises {documented}, "
        f"the metric returns {result.value}"
    )

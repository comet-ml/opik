import pytest

from opik.evaluation.metrics.heuristics import contains, equals, levenshtein_ratio

# "café" with a precomposed "é" (U+00E9) and with "e" plus a combining acute
# accent (U+0301): different code points, the same text.
CAFE_COMPOSED = "café"
CAFE_DECOMPOSED = "café"


@pytest.mark.parametrize(
    "output, reference",
    [
        ("Main STRASSE 5", "straße"),
        ("main straße 5", "STRASSE"),
        ("ﬁle not found", "FILE"),
    ],
)
def test_contains__case_insensitive_non_ascii_case_variants__score_one(
    output, reference
):
    metric = contains.Contains(track=False)

    assert metric.score(output=output, reference=reference).value == 1.0


@pytest.mark.parametrize("case_sensitive", [True, False])
@pytest.mark.parametrize(
    "output, reference",
    [
        (f"the {CAFE_DECOMPOSED} menu", CAFE_COMPOSED),
        (f"the {CAFE_COMPOSED} menu", CAFE_DECOMPOSED),
    ],
)
def test_contains__canonically_equivalent_unicode__score_one(
    output, reference, case_sensitive
):
    metric = contains.Contains(case_sensitive=case_sensitive, track=False)

    assert metric.score(output=output, reference=reference).value == 1.0


def test_contains__case_sensitive_non_ascii_case_variants__score_zero():
    metric = contains.Contains(case_sensitive=True, track=False)

    assert metric.score(output="Main STRASSE 5", reference="straße").value == 0.0


def test_equals__case_insensitive_non_ascii_case_variants__score_one():
    metric = equals.Equals(track=False)

    assert metric.score(output="STRASSE", reference="straße").value == 1.0


@pytest.mark.parametrize("case_sensitive", [True, False])
def test_equals__canonically_equivalent_unicode__score_one(case_sensitive):
    metric = equals.Equals(case_sensitive=case_sensitive, track=False)

    assert metric.score(output=CAFE_DECOMPOSED, reference=CAFE_COMPOSED).value == 1.0


@pytest.mark.parametrize("case_sensitive", [True, False])
def test_equals__canonically_equivalent_unicode_inside_containers__score_one(
    case_sensitive,
):
    metric = equals.Equals(case_sensitive=case_sensitive, track=False)

    result = metric.score(
        output={"drink": [CAFE_DECOMPOSED]},
        reference={"drink": [CAFE_COMPOSED]},
    )

    assert result.value == 1.0


def test_equals__case_insensitive_non_ascii_case_variants_inside_containers__score_one():
    metric = equals.Equals(track=False)

    result = metric.score(output=["STRASSE"], reference=["straße"])

    assert result.value == 1.0


def test_equals__case_sensitive_non_ascii_case_variants__score_zero():
    metric = equals.Equals(case_sensitive=True, track=False)

    assert metric.score(output="STRASSE", reference="straße").value == 0.0


def test_levenshtein_ratio__case_insensitive_non_ascii_case_variants__score_one():
    metric = levenshtein_ratio.LevenshteinRatio(track=False)

    assert metric.score(output="STRASSE", reference="straße").value == 1.0


@pytest.mark.parametrize("case_sensitive", [True, False])
def test_levenshtein_ratio__canonically_equivalent_unicode__score_one(
    case_sensitive,
):
    metric = levenshtein_ratio.LevenshteinRatio(
        case_sensitive=case_sensitive, track=False
    )

    assert metric.score(output=CAFE_DECOMPOSED, reference=CAFE_COMPOSED).value == 1.0


@pytest.mark.parametrize("case_sensitive", [True, False])
def test_equals__canonically_equivalent_dict_keys_with_swapped_values__score_zero(
    case_sensitive,
):
    metric = equals.Equals(case_sensitive=case_sensitive, track=False)

    result = metric.score(
        output={CAFE_DECOMPOSED: 1, CAFE_COMPOSED: 2},
        reference={CAFE_DECOMPOSED: 2, CAFE_COMPOSED: 1},
    )

    assert result.value == 0.0


@pytest.mark.parametrize("case_sensitive", [True, False])
def test_equals__canonically_equivalent_dict_keys_with_same_values__score_one(
    case_sensitive,
):
    metric = equals.Equals(case_sensitive=case_sensitive, track=False)

    result = metric.score(
        output={CAFE_DECOMPOSED: 1, CAFE_COMPOSED: 2},
        reference={CAFE_COMPOSED: 2, CAFE_DECOMPOSED: 1},
    )

    assert result.value == 1.0


@pytest.mark.parametrize("case_sensitive", [True, False])
def test_contains__reference_starting_with_combining_mark__score_one(
    case_sensitive,
):
    metric = contains.Contains(case_sensitive=case_sensitive, track=False)

    assert metric.score(output="áb", reference="́b").value == 1.0

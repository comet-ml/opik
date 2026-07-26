import pytest

from opik.evaluation import evaluation_result, scorers, test_case, test_result
from opik.evaluation.scorers import classification


def _result(predicted, reference, trial_id=1):
    task_output = {} if predicted is _MISSING else {"predicted_label": predicted}
    dataset_item_content = {} if reference is _MISSING else {"label": reference}
    return test_result.TestResult(
        test_case=test_case.TestCase(
            trace_id=f"trace-{trial_id}",
            dataset_item_id=f"item-{trial_id}",
            task_output=task_output,
            dataset_item_content=dataset_item_content,
        ),
        score_results=[],
        trial_id=trial_id,
    )


def _results(pairs):
    return [
        _result(predicted, reference, trial_id)
        for trial_id, (reference, predicted) in enumerate(pairs, start=1)
    ]


_MISSING = object()

# (truth, prediction) pairs. Per-class values, hand-computed:
#   a: precision 3/5, recall 3/4, f1 2/3
#   b: precision 1/2, recall 1/3, f1 2/5
#   c: precision 2/3, recall 2/3, f1 2/3
# Support is a=4, b=3, c=3.
THREE_CLASS = [
    ("a", "a"),
    ("a", "a"),
    ("a", "a"),
    ("a", "b"),
    ("b", "a"),
    ("b", "b"),
    ("b", "c"),
    ("c", "c"),
    ("c", "a"),
    ("c", "c"),
]


# tp=2, fp=1, fn=2 for "spam": precision 2/3, recall 1/2, f1 4/7.
BINARY = [
    ("spam", "spam"),
    ("spam", "spam"),
    ("spam", "ham"),
    ("spam", "ham"),
    ("ham", "spam"),
    ("ham", "ham"),
]


def _score(factory, pairs, **kwargs):
    kwargs.setdefault("output_key", "predicted_label")
    kwargs.setdefault("reference_key", "label")
    return factory(**kwargs)(_results(pairs))


def test_f1_score__macro():
    scorer = classification.f1_score(
        output_key="predicted_label", reference_key="label", average="macro"
    )

    score = scorer(_results(THREE_CLASS))

    assert score.value == pytest.approx(26 / 45)


def test_precision__macro():
    score = _score(classification.precision, THREE_CLASS, average="macro")

    assert score.value == pytest.approx(53 / 90)


def test_recall__macro():
    score = _score(classification.recall, THREE_CLASS, average="macro")

    assert score.value == pytest.approx(7 / 12)


def test_precision__weighted():
    score = _score(classification.precision, THREE_CLASS, average="weighted")

    assert score.value == pytest.approx(0.59)


def test_recall__weighted():
    score = _score(classification.recall, THREE_CLASS, average="weighted")

    assert score.value == pytest.approx(0.6)


def test_f1_score__weighted():
    score = _score(classification.f1_score, THREE_CLASS, average="weighted")

    assert score.value == pytest.approx(44 / 75)


@pytest.mark.parametrize(
    "factory",
    [classification.precision, classification.recall, classification.f1_score],
)
def test_micro_average__equals_accuracy(factory):
    score = _score(factory, THREE_CLASS, average="micro")

    assert score.value == pytest.approx(0.6)


def test_precision__binary():
    score = _score(
        classification.precision, BINARY, average="binary", positive_label="spam"
    )

    assert score.value == pytest.approx(2 / 3)


def test_recall__binary():
    score = _score(
        classification.recall, BINARY, average="binary", positive_label="spam"
    )

    assert score.value == pytest.approx(1 / 2)


def test_f1_score__binary():
    score = _score(
        classification.f1_score, BINARY, average="binary", positive_label="spam"
    )

    assert score.value == pytest.approx(4 / 7)


def test_score_name__defaults_to_metric_and_average():
    score = _score(classification.f1_score, THREE_CLASS, average="macro")

    assert score.name == "f1_score_macro"


def test_custom_name__overrides_the_default():
    score = _score(
        classification.f1_score, THREE_CLASS, average="macro", name="router_f1"
    )

    assert score.name == "router_f1"


# "c" is truth for one row but never predicted: tp=0, fp=0, so precision's
# denominator is zero.
NEVER_PREDICTED = [("a", "a"), ("b", "b"), ("c", "a")]


def test_precision__class_never_predicted__contributes_zero():
    score = _score(classification.precision, NEVER_PREDICTED, average="macro")

    assert score.value == pytest.approx(0.5)


def test_recall__class_never_predicted__contributes_zero():
    score = _score(classification.recall, NEVER_PREDICTED, average="macro")

    assert score.value == pytest.approx(2 / 3)


def test_rows_missing_either_key__skipped_without_changing_score():
    results = _results(THREE_CLASS) + [
        _result(_MISSING, "a", 11),
        _result("a", _MISSING, 12),
    ]
    scorer = classification.f1_score(
        output_key="predicted_label", reference_key="label", average="macro"
    )

    score = scorer(results)

    assert score.value == pytest.approx(26 / 45)
    assert score.metadata["n_samples"] == 10
    assert score.metadata["n_skipped"] == 2
    assert "2" in score.reason


def test_all_rows_missing__scoring_failed():
    scorer = classification.precision(
        output_key="predicted_label", reference_key="label", average="macro"
    )

    score = scorer([_result(_MISSING, _MISSING, 1)])

    assert score.scoring_failed is True
    assert score.value == 0.0


@pytest.mark.parametrize("malformed", [["spam"], {"label": "spam"}])
def test_unhashable_prediction__scoring_failed(malformed):
    score = _score(
        classification.f1_score, [("spam", malformed), ("ham", "ham")], average="macro"
    )

    assert score.scoring_failed is True
    assert score.value == 0.0


def test_metadata__reports_average_labels_and_counts():
    score = _score(classification.recall, THREE_CLASS, average="weighted")

    assert score.metadata["average"] == "weighted"
    assert score.metadata["labels"] == ["a", "b", "c"]
    assert score.metadata["n_samples"] == 10
    assert score.metadata["n_skipped"] == 0


def test_labels_of_mixed_types__does_not_raise():
    score = _score(classification.f1_score, [(1, 1), ("b", "b")], average="macro")

    assert score.value == pytest.approx(1.0)


# Every test below calls only the factory, never the returned closure - that is
# the assertion. compute_experiment_scores swallows call-time exceptions into a
# log warning, so a bad config has to fail before evaluation starts.
ALL_FACTORIES = [
    classification.precision,
    classification.recall,
    classification.f1_score,
]


@pytest.mark.parametrize("factory", ALL_FACTORIES)
@pytest.mark.parametrize("blank", ["", "   "])
def test_blank_output_key__raises_at_construction(factory, blank):
    with pytest.raises(ValueError, match="output_key"):
        factory(output_key=blank, reference_key="label")


@pytest.mark.parametrize("factory", ALL_FACTORIES)
@pytest.mark.parametrize("blank", ["", "   "])
def test_blank_reference_key__raises_at_construction(factory, blank):
    with pytest.raises(ValueError, match="reference_key"):
        factory(output_key="predicted_label", reference_key=blank)


@pytest.mark.parametrize("factory", ALL_FACTORIES)
@pytest.mark.parametrize("blank", ["", "   "])
def test_blank_name__raises_at_construction(factory, blank):
    with pytest.raises(ValueError, match="name"):
        factory(output_key="predicted_label", reference_key="label", name=blank)


@pytest.mark.parametrize("factory", ALL_FACTORIES)
def test_unknown_average__raises_at_construction(factory):
    with pytest.raises(ValueError, match="average"):
        factory(output_key="predicted_label", reference_key="label", average="mikro")


@pytest.mark.parametrize("factory", ALL_FACTORIES)
def test_binary_without_positive_label__raises_at_construction(factory):
    with pytest.raises(ValueError, match="positive_label"):
        factory(output_key="predicted_label", reference_key="label", average="binary")


@pytest.mark.parametrize("factory", ALL_FACTORIES)
@pytest.mark.parametrize("average", ["macro", "micro", "weighted"])
def test_positive_label_with_non_binary_average__raises_at_construction(
    factory, average
):
    with pytest.raises(ValueError, match="positive_label"):
        factory(
            output_key="predicted_label",
            reference_key="label",
            average=average,
            positive_label="spam",
        )


def test_binary_score_name__includes_positive_label():
    score = _score(
        classification.f1_score, BINARY, average="binary", positive_label="spam"
    )

    assert score.name == "f1_score_binary_spam"


def test_binary_positive_label_absent_from_data__scoring_failed():
    scorer = classification.f1_score(
        output_key="predicted_label",
        reference_key="label",
        average="binary",
        positive_label="urgent",
    )

    score = scorer(_results(BINARY))

    assert score.scoring_failed is True
    assert score.value == 0.0
    assert "urgent" in score.reason


def test_metadata_labels__total_order_across_types_that_stringify_alike():
    score = _score(classification.f1_score, [(1, 1), ("1", "1")], average="macro")

    assert score.metadata["labels"] == [1, "1"]


@pytest.mark.parametrize("factory", ALL_FACTORIES)
def test_public_factories__document_their_contract(factory):
    assert factory.__doc__


def test_scorers_package__exposes_classification():
    assert "classification" in scorers.__all__


def test_scorer_runs_through_compute_experiment_scores():
    scorer = classification.f1_score(
        output_key="predicted_label", reference_key="label", average="macro"
    )

    scores = evaluation_result.compute_experiment_scores(
        experiment_scoring_functions=[scorer],
        test_results=_results(THREE_CLASS),
    )

    assert [score.name for score in scores] == ["f1_score_macro"]
    assert scores[0].value == pytest.approx(26 / 45)

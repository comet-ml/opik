import pytest

from opik.evaluation.metrics import Contains,score_result



class TestContainsMetric:

    @pytest.mark.parametrize(
        "init_reference, score_reference, should_raise",
        [
            (None, None, True),   # 1: None in both -> Raise
            ("ref", None, False), # 2: Init set, score None -> Use init
            (None, "ref", False), # 3: Init None, score set -> Use score ref
            ("", "ref", False),   # 4: Init empty, score set -> Use score ref
            ("ref", "", True),    # 5: Init set, score empty -> Raise ValueError
            ("", "", True),       # 6: Init empty, score empty -> Raise ValueError
        ],
        ids=[
            "None_None",
            "InitSet_ScoreNone",
            "InitNone_ScoreSet",
            "InitEmpty_ScoreSet",
            "InitSet_ScoreEmpty",
            "InitEmpty_ScoreEmpty",
        ]
    )
    def test_reference_none_and_empty_behavior(self, init_reference, score_reference, should_raise):
        """
        Tests correct ValueError handling for None and empty string references.
        """
        metric = Contains(reference=init_reference)
        output = "some output string"

        if should_raise:
            with pytest.raises(ValueError, match="Reference string"):
                metric.score(output=output, reference=score_reference)
        else:
            result = metric.score(output=output, reference=score_reference)
            assert isinstance(result, score_result.ScoreResult)



    @pytest.mark.parametrize(
        "output, reference, case_sensitive, expected",
        [
            ("Hello, World!", "world", False, 1.0),  # insensitive match
            ("Hello, World!", "world", True, 0.0),   # sensitive mismatch
            ("Hello World", "Hello", True, 1.0),     # sensitive match
            ("Hello World", "HI", False, 0.0),      # Not found
        ],
        ids=[
            "CaseInsensitive_Match",
            "CaseSensitive_Mismatch",
            "CaseSensitive_Match",
            "NoMatch",
        ],
    )
    def test_case_sensitivity_and_matching(self, output, reference, case_sensitive, expected):
        """
        Tests that case sensitivity flag correctly affects matching.
        """
        metric = Contains(case_sensitive=case_sensitive)
        result = metric.score(output=output, reference=reference)
        assert result.value == expected


    def test_empty_reference_in_init_raises(self):
        """
        Tests that initializing metric with empty reference raises ValueError.
        """
        metric = Contains(reference="")
        output = "some text"
        with pytest.raises(ValueError, match="Reference string cannot be empty"):
            metric.score(output=output)

    def test_empty_reference_in_score_raises(self):
        """
        Tests that passing empty string to score() raises ValueError.
        """
        metric = Contains()
        output = "test"
        with pytest.raises(ValueError, match="Reference string cannot be empty"):
            metric.score(output=output, reference="")

    def test_none_reference_in_both_places_raises(self):
        """
        Tests that None reference in both init and score raises ValueError.
        """
        metric = Contains()
        with pytest.raises(ValueError, match="Reference string must be provided"):
            metric.score(output="something")

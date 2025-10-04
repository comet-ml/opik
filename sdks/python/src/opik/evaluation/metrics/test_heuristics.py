import pytest
# Assuming Contains and score_result are correctly imported
from opik.evaluation.metrics import Contains
from opik.evaluation.metrics import score_result 


class TestContainsMetric:
    
    @pytest.mark.parametrize(
        "init_reference, score_reference, should_raise",
        [
            (None, None, True),          # Case 1: None in __init__ and None in score -> Raise
            ("ref", None, False),        # Case 2: Init set, score is None -> Use Init reference (Pass)
            (None, "ref", False),        # Case 3: Init None, score set -> Use score reference (Pass)
            ("", "ref", False),          # Case 4: Init empty, score set -> Use score reference (Pass)
            ("ref", "", False),          # Case 5: Init set, score is "" -> Falls back to Init reference (Pass)
            ("", "", False),             # Case 6: Init empty, score is "" -> Falls back to Init reference (Pass)
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
    def test_reference_is_none_or_empty_fallback(self, init_reference, score_reference, should_raise):
        """
        Tests that both None and "" in the score method trigger fallback to the 
        initialization reference, and only strictly unresolvable references raise ValueError.
        """
        metric = Contains(reference=init_reference)
        output = "some output string"

        if should_raise:
            with pytest.raises(ValueError, match="Reference string must be provided"):
                metric.score(output=output, reference=score_reference)
        else:
            # Should not raise, just ensure it runs
            try:
                result = metric.score(output=output, reference=score_reference)
                assert isinstance(result, score_result.ScoreResult)
            except ValueError as e:
                pytest.fail(f"Unexpected ValueError: {e}")

    # --- Tests for Empty String as a Successful Reference ---

    @pytest.mark.parametrize(
        "output, case_sensitive", 
        [
            ("hello world", False),
            ("hello world", True),
            ("", False),
        ],
        ids=[
            "EmptyRef_InInit_InOutput_CI", 
            "EmptyRef_InInit_InOutput_CS",
            "EmptyRef_InInit_EmptyOutput",
        ]
    )
    def test_empty_reference_is_always_contained_via_init(self, output, case_sensitive):
        """
        Tests the core rule: an empty string set in __init__ is a valid reference
        and is always contained in any output string (score 1.0).
        """
        expected_score = 1.0
        
        # Test 1: Reference set in __init__ (Should succeed)
        metric = Contains(reference="", case_sensitive=case_sensitive)
        result = metric.score(output=output)
        assert result.value == expected_score


    def test_empty_reference_as_score_arg_raises_error_without_default(self):
        """
        Tests the requirement that providing an empty string to score() 
        without an __init__ default results in a ValueError, confirming that 
        "" is treated as a 'missing' signal/fallback trigger.
        """
        metric = Contains()
        output = "some output"
        
        # Passing "" to score() triggers fallback to self._reference (None), which raises ValueError.
        with pytest.raises(ValueError, match="Reference string must be provided"):
             metric.score(output=output, reference="")
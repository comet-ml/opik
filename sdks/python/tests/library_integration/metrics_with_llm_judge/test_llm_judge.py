import pytest

from opik.evaluation.suite_evaluators import LLMJudge


pytestmark = pytest.mark.usefixtures("ensure_openai_configured")


def assert_llm_judge_score_result(result, expected_name: str) -> None:
    """Assert that an LLMJudge score result is valid."""
    assert result.scoring_failed is False
    assert result.name == expected_name
    assert isinstance(result.value, bool)
    assert isinstance(result.reason, str)
    assert len(result.reason) > 0


class TestLLMJudgeScore:
    def test_score__single_assertion__returns_result(self):
        assertion = "Response is factually accurate"
        evaluator = LLMJudge(
            assertions=[assertion],
            track=False,
        )

        results = evaluator.score(
            input="What is the capital of France?",
            output="The capital of France is Paris.",
        )

        assert len(results) == 1
        assert_llm_judge_score_result(results[0], expected_name=assertion)
        assert results[0].value is True

    def test_score__multiple_assertions__returns_multiple_results(self):
        assertion_accurate = "Response is factually accurate"
        assertion_helpful = "Response is helpful to the user"
        evaluator = LLMJudge(
            assertions=[assertion_accurate, assertion_helpful],
            track=False,
        )

        results = evaluator.score(
            input="What is the capital of France?",
            output="The capital of France is Paris. It is a beautiful city known for the Eiffel Tower.",
        )

        assert len(results) == 2
        result_names = {r.name for r in results}
        assert assertion_accurate in result_names
        assert assertion_helpful in result_names
        for result in results:
            assert_llm_judge_score_result(result, expected_name=result.name)

    def test_score__custom_name__uses_custom_name_prefix(self):
        assertion = "Test assertion"
        evaluator = LLMJudge(
            assertions=[assertion],
            name="my_custom_judge",
            track=False,
        )

        results = evaluator.score(
            input="Hello",
            output="Hello there!",
        )

        assert len(results) == 1
        # The assertion text is used as the score name (returned by LLM)
        assert_llm_judge_score_result(results[0], expected_name=assertion)

    def test_score__failing_assertion__returns_false(self):
        assertion = "Response is factually accurate"
        evaluator = LLMJudge(
            assertions=[assertion],
            track=False,
        )

        results = evaluator.score(
            input="What is the capital of France?",
            output="The capital of France is London.",
        )

        assert len(results) == 1
        assert results[0].value is False
        assert_llm_judge_score_result(results[0], expected_name=assertion)


class TestLLMJudgeAsyncScore:
    @pytest.mark.asyncio
    async def test_ascore__single_assertion__returns_result(self):
        assertion = "Response is factually accurate"
        evaluator = LLMJudge(
            assertions=[assertion],
            track=False,
        )

        results = await evaluator.ascore(
            input="What is the capital of Germany?",
            output="The capital of Germany is Berlin.",
        )

        assert len(results) == 1
        assert_llm_judge_score_result(results[0], expected_name=assertion)
        assert results[0].value is True

    @pytest.mark.asyncio
    async def test_ascore__multiple_assertions__returns_multiple_results(self):
        assertion_accurate = "Response is factually accurate"
        assertion_concise = "Response is concise and to the point"
        evaluator = LLMJudge(
            assertions=[assertion_accurate, assertion_concise],
            track=False,
        )

        results = await evaluator.ascore(
            input="What is 5 + 5?",
            output="10",
        )

        assert len(results) == 2
        result_names = {r.name for r in results}
        assert assertion_accurate in result_names
        assert assertion_concise in result_names
        for result in results:
            assert_llm_judge_score_result(result, expected_name=result.name)

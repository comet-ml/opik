import pytest

from opik.evaluation.suite_evaluators import LLMJudge
from ...testlib import assert_helpers


pytestmark = pytest.mark.usefixtures("ensure_openai_configured")


class TestLLMJudgeScore:
    def test_score__single_assertion__returns_result(self):
        evaluator = LLMJudge(
            assertions=[
                {
                    "name": "accurate",
                    "expected_behavior": "Response is factually accurate",
                }
            ],
            model="gpt-4o",
            track=False,
        )

        results = evaluator.score(
            input="What is the capital of France?",
            output="The capital of France is Paris.",
        )

        assert len(results) == 1
        assert results[0].name == "llm_judge_accurate"
        assert_helpers.assert_score_result(results[0])

    def test_score__multiple_assertions__returns_multiple_results(self):
        evaluator = LLMJudge(
            assertions=[
                {
                    "name": "accurate",
                    "expected_behavior": "Response is factually accurate",
                },
                {
                    "name": "helpful",
                    "expected_behavior": "Response is helpful to the user",
                },
            ],
            model="gpt-4o",
            track=False,
        )

        results = evaluator.score(
            input="What is the capital of France?",
            output="The capital of France is Paris. It is a beautiful city known for the Eiffel Tower.",
        )

        assert len(results) == 2
        assert results[0].name == "llm_judge_accurate"
        assert results[1].name == "llm_judge_helpful"
        for result in results:
            assert_helpers.assert_score_result(result)

    def test_score__custom_name__uses_custom_name_prefix(self):
        evaluator = LLMJudge(
            assertions=[{"name": "test", "expected_behavior": "Test assertion"}],
            model="gpt-4o",
            name="my_custom_judge",
            track=False,
        )

        results = evaluator.score(
            input="Hello",
            output="Hello there!",
        )

        assert len(results) == 1
        assert results[0].name == "my_custom_judge_test"
        assert_helpers.assert_score_result(results[0])

    def test_score__failing_assertion__returns_false(self):
        evaluator = LLMJudge(
            assertions=[
                {
                    "name": "accurate",
                    "expected_behavior": "Response is factually accurate",
                }
            ],
            model="gpt-4o",
            track=False,
        )

        results = evaluator.score(
            input="What is the capital of France?",
            output="The capital of France is London.",
        )

        assert len(results) == 1
        assert results[0].value is False
        assert_helpers.assert_score_result(results[0])


class TestLLMJudgeAsyncScore:
    @pytest.mark.asyncio
    async def test_ascore__single_assertion__returns_result(self):
        evaluator = LLMJudge(
            assertions=[
                {
                    "name": "accurate",
                    "expected_behavior": "Response is factually accurate",
                }
            ],
            model="gpt-4o",
            track=False,
        )

        results = await evaluator.ascore(
            input="What is the capital of Germany?",
            output="The capital of Germany is Berlin.",
        )

        assert len(results) == 1
        assert results[0].name == "llm_judge_accurate"
        assert_helpers.assert_score_result(results[0])

    @pytest.mark.asyncio
    async def test_ascore__multiple_assertions__returns_multiple_results(self):
        evaluator = LLMJudge(
            assertions=[
                {
                    "name": "accurate",
                    "expected_behavior": "Response is factually accurate",
                },
                {
                    "name": "concise",
                    "expected_behavior": "Response is concise and to the point",
                },
            ],
            model="gpt-4o",
            track=False,
        )

        results = await evaluator.ascore(
            input="What is 5 + 5?",
            output="10",
        )

        assert len(results) == 2
        for result in results:
            assert_helpers.assert_score_result(result)

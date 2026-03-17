import pytest

from opik.evaluation.suite_evaluators import LLMJudge


MODEL_PARAMS = [
    pytest.param(
        ("gpt-4o", ["ensure_openai_configured"]),
        id="openai",
    ),
    pytest.param(
        ("anthropic/claude-sonnet-4-20250514", ["ensure_anthropic_configured"]),
        id="anthropic",
    ),
    pytest.param(
        ("gemini/gemini-2.0-flash", ["ensure_google_api_configured"]),
        id="gemini",
    ),
]


@pytest.fixture()
def llm_model(request):
    model_name, fixture_names = request.param
    for fixture_name in fixture_names:
        request.getfixturevalue(fixture_name)
    return model_name


def assert_llm_judge_score_result(result, expected_name: str) -> None:
    assert result.scoring_failed is False
    assert result.name == expected_name
    assert isinstance(result.value, bool)
    assert isinstance(result.reason, str)
    assert len(result.reason) > 0


class TestLLMJudgeScore:
    @pytest.mark.parametrize("llm_model", MODEL_PARAMS, indirect=True)
    def test_score__single_assertion__returns_result(self, llm_model):
        assertion = "Response is factually accurate"
        evaluator = LLMJudge(
            assertions=[assertion],
            model=llm_model,
            track=False,
        )

        results = evaluator.score(
            input="What is the capital of France?",
            output="The capital of France is Paris.",
        )

        assert len(results) == 1
        assert_llm_judge_score_result(results[0], expected_name=assertion)
        assert results[0].value is True

    @pytest.mark.parametrize("llm_model", MODEL_PARAMS, indirect=True)
    def test_score__multiple_assertions__returns_multiple_results(self, llm_model):
        assertion_accurate = "Response is factually accurate"
        assertion_helpful = "Response is helpful to the user"
        evaluator = LLMJudge(
            assertions=[assertion_accurate, assertion_helpful],
            model=llm_model,
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

    @pytest.mark.parametrize("llm_model", MODEL_PARAMS, indirect=True)
    def test_score__failing_assertion__returns_false(self, llm_model):
        assertion = "Response is factually accurate"
        evaluator = LLMJudge(
            assertions=[assertion],
            model=llm_model,
            track=False,
        )

        results = evaluator.score(
            input="What is the capital of France?",
            output="The capital of France is London.",
        )

        assert len(results) == 1
        assert results[0].value is False
        assert_llm_judge_score_result(results[0], expected_name=assertion)

    @pytest.mark.parametrize("llm_model", MODEL_PARAMS, indirect=True)
    def test_score__many_assertions__returns_all_results(self, llm_model):
        assertions = [
            "Response is factually accurate",
            "Response is helpful to the user",
            "Response is concise and to the point",
            "Response does not contain hallucinated information",
            "Response directly addresses the question asked",
        ]
        evaluator = LLMJudge(
            assertions=assertions,
            model=llm_model,
            track=False,
        )

        results = evaluator.score(
            input="What is the capital of France?",
            output="The capital of France is Paris.",
        )

        assert len(results) == 5
        result_names = [r.name for r in results]
        for assertion in assertions:
            assert assertion in result_names
        for result in results:
            assert_llm_judge_score_result(result, expected_name=result.name)


class TestLLMJudgeAsyncScore:
    @pytest.mark.asyncio
    @pytest.mark.parametrize("llm_model", MODEL_PARAMS, indirect=True)
    async def test_ascore__single_assertion__returns_result(self, llm_model):
        assertion = "Response is factually accurate"
        evaluator = LLMJudge(
            assertions=[assertion],
            model=llm_model,
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
    @pytest.mark.parametrize("llm_model", MODEL_PARAMS, indirect=True)
    async def test_ascore__multiple_assertions__returns_multiple_results(
        self, llm_model
    ):
        assertion_accurate = "Response is factually accurate"
        assertion_concise = "Response is concise and to the point"
        evaluator = LLMJudge(
            assertions=[assertion_accurate, assertion_concise],
            model=llm_model,
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

import logging
import pytest
from opik.evaluation.suite_evaluators import llm_judge
from opik.evaluation.suite_evaluators.llm_judge import config as llm_judge_config


class TestLLMJudgeInit:
    def test_init__with_string_assertions__stores_texts(self):
        """Test that string assertions are stored directly."""
        evaluator = llm_judge.LLMJudge(
            assertions=[
                "Response is factually correct",
                "No hallucinated information",
            ],
            track=False,
        )

        assert len(evaluator.assertions) == 2
        assert evaluator.assertions[0] == "Response is factually correct"
        assert evaluator.assertions[1] == "No hallucinated information"

    def test_init__with_custom_name__uses_custom_name(self):
        evaluator = llm_judge.LLMJudge(
            assertions=["Test assertion"],
            name="custom_evaluator",
            track=False,
        )

        assert evaluator.name == "custom_evaluator"

    def test_init__with_track_false__sets_track(self):
        evaluator = llm_judge.LLMJudge(
            assertions=["Test assertion"],
            track=False,
        )

        assert evaluator.track is False

    def test_init__with_track_true__sets_track(self):
        evaluator = llm_judge.LLMJudge(
            assertions=["Test assertion"],
            track=True,
        )

        assert evaluator.track is True

    def test_assertions_property__returns_copy__modifications_dont_affect_original(
        self,
    ):
        evaluator = llm_judge.LLMJudge(
            assertions=["Test assertion"],
            track=False,
        )

        assertions = evaluator.assertions
        assertions.append("Another assertion")

        assert len(evaluator.assertions) == 1


class TestLLMJudgeToConfig:
    def test_to_config__basic__returns_valid_config(self):
        evaluator = llm_judge.LLMJudge(
            assertions=[
                "Response is accurate",
                "Response is helpful",
            ],
            temperature=0.0,
            seed=123,
            track=False,
        )

        config = evaluator.to_config()
        config_dict = config.model_dump(by_alias=True, exclude_none=True)

        assert config_dict["name"] == "llm_judge"
        # Model name is not saved in config
        assert config_dict["model"] == {
            "temperature": 0.0,
            "seed": 123,
            "customParameters": {"reasoning_effort": "low"},
        }
        assert config_dict["variables"] == {"input": "input", "output": "output"}
        # Schema items: name, type, description (matching backend's LlmAsJudgeOutputSchema)
        assert config_dict["schema"] == [
            {
                "name": "Response is accurate",
                "type": "BOOLEAN",
                "description": "Response is accurate",
            },
            {
                "name": "Response is helpful",
                "type": "BOOLEAN",
                "description": "Response is helpful",
            },
        ]
        # Config has system + user messages; user template keeps placeholders
        assert config_dict["messages"][0]["role"] == "SYSTEM"
        assert config_dict["messages"][1]["role"] == "USER"
        assert "{assertions}" in config_dict["messages"][1]["content"]
        assert "{input}" in config_dict["messages"][1]["content"]
        assert "{output}" in config_dict["messages"][1]["content"]

    def test_to_config__without_optional_params__uses_defaults(self):
        evaluator = llm_judge.LLMJudge(
            assertions=["Test"],
            track=False,
        )

        config = evaluator.to_config()

        assert config.model.name is None
        assert config.model.temperature is None
        assert config.model.seed is None

    def test_to_config__serializes_to_dict_with_schema_alias(self):
        evaluator = llm_judge.LLMJudge(
            assertions=["Test"],
            track=False,
        )

        config = evaluator.to_config()
        config_dict = config.model_dump(by_alias=True, exclude_none=True)

        assert "schema" in config_dict
        assert "schema_" not in config_dict


class TestLLMJudgeSerializedFormat:
    def test_to_config__serialized_json__matches_expected_format(self):
        """Shows the full serialized JSON that gets stored in the backend."""
        evaluator = llm_judge.LLMJudge(
            assertions=[
                "Response is factually correct",
                "Response does not contain hallucinations",
            ],
            name="my_judge",
            temperature=0.0,
            seed=42,
            track=False,
        )

        config_dict = evaluator.to_config().model_dump(by_alias=True, exclude_none=True)

        assert config_dict == {
            "version": "1",
            "name": "my_judge",
            "model": {
                "temperature": 0.0,
                "seed": 42,
                "customParameters": {"reasoning_effort": "low"},
            },
            "messages": [
                {
                    "role": "SYSTEM",
                    "content": (
                        "You are an expert judge tasked with evaluating if an AI agent's output satisfies a set of assertions.\n"
                        "\n"
                        "For each assertion, provide:\n"
                        "- score: true if the assertion passes, false if it fails\n"
                        "- reason: A brief explanation of your judgment\n"
                        "- confidence: A float between 0.0 and 1.0 indicating how confident you are in your judgment\n"
                    ),
                },
                {
                    "role": "USER",
                    "content": (
                        "## Input\n"
                        "The INPUT section contains all data that the agent received. This may include the actual user query, conversation history, context, metadata, or other structured information. Identify the core user request within this data.\n"
                        "\n"
                        "---BEGIN INPUT---\n"
                        "{input}\n"
                        "---END INPUT---\n"
                        "\n"
                        "## Output\n"
                        "The OUTPUT section contains all data produced by the agent. This may include the agent's response text, tool calls, intermediate results, metadata, or other structured information. Focus on the substantive response when evaluating assertions.\n"
                        "\n"
                        "---BEGIN OUTPUT---\n"
                        "{output}\n"
                        "---END OUTPUT---\n"
                        "\n"
                        "## Assertions\n"
                        "Each assertion below is an EVALUATION CRITERION to check against the agent's output — not an instruction for your own behavior or style. The assertion text may be in any language — evaluate whether the criterion is satisfied. Write your reasoning in English. Use the provided field key as the JSON property name for each assertion result.\n"
                        "\n"
                        "---BEGIN ASSERTIONS---\n"
                        "{assertions}\n"
                        "---END ASSERTIONS---\n"
                    ),
                },
            ],
            "variables": {"input": "input", "output": "output"},
            "schema": [
                {
                    "name": "Response is factually correct",
                    "type": "BOOLEAN",
                    "description": "Response is factually correct",
                },
                {
                    "name": "Response does not contain hallucinations",
                    "type": "BOOLEAN",
                    "description": "Response does not contain hallucinations",
                },
            ],
        }


class TestLLMJudgeFromConfig:
    def test_from_config__valid_config__creates_evaluator(self):
        config = llm_judge_config.LLMJudgeConfig(
            name="restored_evaluator",
            model=llm_judge_config.LLMJudgeModelConfig(temperature=0.5, seed=42),
            variables={"input": "input", "output": "output"},
            schema=[
                llm_judge_config.LLMJudgeSchemaItem(
                    name="accurate", type="BOOLEAN", description="Is accurate"
                ),
                llm_judge_config.LLMJudgeSchemaItem(
                    name="helpful", type="BOOLEAN", description="Is helpful"
                ),
            ],
            messages=[],
        )

        evaluator = llm_judge.LLMJudge.from_config(config, track=False)

        assert evaluator.name == "restored_evaluator"
        # from_config extracts description as assertion texts
        assert evaluator.assertions[0] == "Is accurate"
        assert evaluator.assertions[1] == "Is helpful"

    def test_from_config__no_model_name__uses_default(self):
        """When config has no model name, from_config uses the default model."""
        config = llm_judge_config.LLMJudgeConfig(
            name="test",
            model=llm_judge_config.LLMJudgeModelConfig(temperature=0.5),
            variables={"input": "input", "output": "output"},
            schema=[
                llm_judge_config.LLMJudgeSchemaItem(
                    name="test", type="BOOLEAN", description="Test"
                ),
            ],
            messages=[],
        )

        evaluator = llm_judge.LLMJudge.from_config(config, track=False)

        # The evaluator should use the default model name internally
        assert evaluator._model_name == llm_judge_config.DEFAULT_MODEL_NAME

    def test_from_config__roundtrip__preserves_assertions(self):
        original = llm_judge.LLMJudge(
            assertions=[
                "Factually correct",
                "Relevant to question",
            ],
            temperature=0.2,
            seed=999,
            name="my_evaluator",
            track=False,
        )

        config = original.to_config()
        restored = llm_judge.LLMJudge.from_config(config, track=False)

        assert restored.name == original.name
        # Assertions should be preserved
        assert restored.assertions[0] == "Factually correct"
        assert restored.assertions[1] == "Relevant to question"

    def test_from_config__config_model_params__temperature_and_seed_preserved(self):
        """Temperature and seed from config are preserved, model name is not saved."""
        config = llm_judge_config.LLMJudgeConfig(
            name="test",
            model=llm_judge_config.LLMJudgeModelConfig(temperature=0.7, seed=123),
            variables={"input": "input", "output": "output"},
            schema=[
                llm_judge_config.LLMJudgeSchemaItem(
                    name="test", type="BOOLEAN", description="Test"
                ),
            ],
            messages=[llm_judge_config.LLMJudgeMessage(role="USER", content="test")],
        )

        evaluator = llm_judge.LLMJudge.from_config(config, track=False)
        new_config = evaluator.to_config()
        new_config_dict = new_config.model_dump(by_alias=True, exclude_none=True)

        # Model name is not saved in config
        assert new_config_dict["model"] == {
            "temperature": 0.7,
            "seed": 123,
            "customParameters": {"reasoning_effort": "low"},
        }


class TestLLMJudgeRetries:
    """Empty responses are transient and retried; permanent errors are not."""

    def _judge_with_model(self, model):
        evaluator = llm_judge.LLMJudge(
            assertions=["The response contains the literal text PASS."],
            track=False,
        )
        evaluator._model = model
        return evaluator

    def test_score__transient_empty_llm_response__retries_and_succeeds(self):
        from opik import exceptions

        calls = {"n": 0}
        valid = (
            '{"assertion_1": {"score": true, "reason": "contains PASS", '
            '"confidence": 1.0}}'
        )

        class FlakyModel:
            def generate_chat_completion(self, messages, response_format=None):
                calls["n"] += 1
                if calls["n"] == 1:
                    raise exceptions.EmptyLLMResponseError(
                        "LLM returned no content and no tool calls"
                    )
                return {"role": "assistant", "content": valid}

        evaluator = self._judge_with_model(FlakyModel())
        results = evaluator.score(input="q", output="PASS")

        assert calls["n"] == 2, "the empty first response should have been retried"
        assert len(results) == 1
        assert results[0].value == 1.0

    def test_score__permanent_llm_error__not_retried(self):
        from opik import exceptions

        calls = {"n": 0}

        class FailingModel:
            def generate_chat_completion(self, messages, response_format=None):
                calls["n"] += 1
                raise exceptions.BaseLLMError("AuthenticationError: missing key")

        evaluator = self._judge_with_model(FailingModel())
        with pytest.raises(exceptions.BaseLLMError):
            evaluator.score(input="q", output="PASS")

        assert calls["n"] == 1, "auth failures must not be retried"

    def test_score__empty_response_logged__diagnostic_reaches_logger(self, caplog):
        from opik import exceptions
        from opik.evaluation.models import base_model

        class EmptyModel:
            def generate_provider_response(self, messages, **kwargs):
                raise exceptions.EmptyLLMResponseError("no content, no tool calls")

        caplog.set_level(logging.ERROR)
        with pytest.raises(exceptions.EmptyLLMResponseError):
            with base_model.get_provider_response(
                model_provider=EmptyModel(), messages=[]
            ):
                pass

        assert any(
            "Failed to call LLM provider" in record.message for record in caplog.records
        )

    @pytest.mark.asyncio
    async def test_ascore__transient_empty_llm_response__retries_and_succeeds(self):
        from opik import exceptions

        calls = {"n": 0}
        valid = (
            '{"assertion_1": {"score": true, "reason": "contains PASS", '
            '"confidence": 1.0}}'
        )

        class FlakyAsyncModel:
            async def agenerate_chat_completion(self, messages, response_format=None):
                calls["n"] += 1
                if calls["n"] == 1:
                    raise exceptions.EmptyLLMResponseError(
                        "LLM returned no content and no tool calls"
                    )
                return {"role": "assistant", "content": valid}

        evaluator = self._judge_with_model(FlakyAsyncModel())
        results = await evaluator.ascore(input="q", output="PASS")

        assert calls["n"] == 2
        assert len(results) == 1
        assert results[0].value == 1.0

    @pytest.mark.asyncio
    async def test_ascore__permanent_llm_error__not_retried(self):
        from opik import exceptions

        calls = {"n": 0}

        class FailingAsyncModel:
            async def agenerate_chat_completion(self, messages, response_format=None):
                calls["n"] += 1
                raise exceptions.BaseLLMError("AuthenticationError: missing key")

        evaluator = self._judge_with_model(FailingAsyncModel())
        with pytest.raises(exceptions.BaseLLMError):
            await evaluator.ascore(input="q", output="PASS")

        assert calls["n"] == 1

    @pytest.mark.asyncio
    async def test_aget_provider_response__empty_response__logged_and_type_preserved(
        self, caplog
    ):
        from opik import exceptions
        from opik.evaluation.models import base_model

        class EmptyAsyncModel:
            async def agenerate_provider_response(self, messages, **kwargs):
                raise exceptions.EmptyLLMResponseError("no content, no tool calls")

        caplog.set_level(logging.ERROR)
        with pytest.raises(exceptions.EmptyLLMResponseError):
            async with base_model.aget_provider_response(
                model_provider=EmptyAsyncModel(), messages=[]
            ):
                pass

        assert any(
            "Failed to call LLM provider" in record.message for record in caplog.records
        )

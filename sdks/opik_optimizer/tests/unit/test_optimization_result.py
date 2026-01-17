"""Tests for OptimizationResult class and helper functions."""

import pytest
from unittest.mock import MagicMock, patch

from opik_optimizer import ChatPrompt
from opik_optimizer.optimization_result import OptimizationResult
from opik_optimizer.utils.display import (
    format_float,
    format_prompt_for_plaintext,
    render_rich_result,
)


class TestFormatFloat:
    """Tests for format_float helper function."""

    def test_formats_float_with_default_precision(self) -> None:
        result = format_float(3.14159265)
        assert result == "3.141593"

    def test_formats_float_with_custom_precision(self) -> None:
        result = format_float(3.14159265, digits=2)
        assert result == "3.14"

    def test_formats_zero(self) -> None:
        result = format_float(0.0)
        assert result == "0.000000"

    def test_formats_negative_float(self) -> None:
        result = format_float(-1.5, digits=3)
        assert result == "-1.500"

    def test_passes_through_non_float(self) -> None:
        result = format_float("string_value")
        assert result == "string_value"

    def test_passes_through_integer(self) -> None:
        result = format_float(42)
        assert result == "42"

    def test_passes_through_none(self) -> None:
        result = format_float(None)
        assert result == "None"


class TestFormatPromptForPlaintext:
    """Tests for format_prompt_for_plaintext function."""

    def test_formats_single_prompt(self) -> None:
        prompt = ChatPrompt(
            system="You are helpful.",
            user="What is 2+2?",
        )
        result = format_prompt_for_plaintext(prompt)
        assert "system:" in result
        assert "user:" in result
        assert "You are helpful." in result
        assert "What is 2+2?" in result

    def test_formats_dict_of_prompts(self) -> None:
        prompts = {
            "planner": ChatPrompt(system="Plan the task.", user="{task}"),
            "executor": ChatPrompt(system="Execute the plan.", user="{plan}"),
        }
        result = format_prompt_for_plaintext(prompts)
        assert "[planner]" in result
        assert "[executor]" in result
        assert "Plan the task." in result
        assert "Execute the plan." in result

    def test_truncates_long_content(self) -> None:
        long_content = "x" * 500
        prompt = ChatPrompt(system=long_content, user="short")
        result = format_prompt_for_plaintext(prompt)
        # Content should be truncated
        assert len(result) < 500

    def test_handles_multimodal_content(self) -> None:
        prompt = ChatPrompt(
            messages=[
                {"role": "system", "content": "Analyze image."},
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "What is this?"},
                        {
                            "type": "image_url",
                            "image_url": {"url": "data:image/png;base64,abc"},
                        },
                    ],
                },
            ]
        )
        result = format_prompt_for_plaintext(prompt)
        assert "[multimodal content]" in result


class TestOptimizationResultInitialization:
    """Tests for OptimizationResult initialization."""

    def test_creates_with_minimal_fields(self) -> None:
        prompt = ChatPrompt(system="Test", user="Query")
        result = OptimizationResult(
            prompt=prompt,
            score=0.85,
            metric_name="accuracy",
        )
        assert result.score == 0.85
        assert result.metric_name == "accuracy"
        assert result.optimizer == "Optimizer"

    def test_creates_with_all_fields(self) -> None:
        prompt = ChatPrompt(system="Test", user="Query")
        result = OptimizationResult(
            optimizer="MetaPromptOptimizer",
            prompt=prompt,
            score=0.95,
            metric_name="f1_score",
            optimization_id="opt-123",
            dataset_id="ds-456",
            initial_prompt=ChatPrompt(system="Initial", user="Query"),
            initial_score=0.60,
            details={"rounds": [1, 2, 3], "model": "gpt-4"},
            history=[{"round": 1, "trials": [{"trial_index": 0, "score": 0.7}]}],
            llm_calls=100,
            llm_calls_tools=10,
            llm_cost_total=5.50,
            llm_token_usage_total={"prompt_tokens": 1000, "completion_tokens": 500},
        )
        assert result.optimizer == "MetaPromptOptimizer"
        assert result.optimization_id == "opt-123"
        assert result.initial_score == 0.60
        assert result.llm_cost_total == 5.50
        assert result.details.get("schema_version") is None
        assert result.details_version == "v1"
        assert result.details.get("trials_completed") == 1
        assert result.details.get("rounds_completed") == 1
        assert result.details.get("stop_reason_details") is None

    def test_details_counters_default_from_history(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.5,
            metric_name="accuracy",
            details={"iterations_completed": 3, "trials_used": 4},
        )
        assert result.details.get("rounds_completed") == 0
        assert result.details.get("trials_completed") == 0

    def test_trials_completed_from_nested_history(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.5,
            metric_name="accuracy",
            history=[
                {"round": 0, "trials": [{"trial_index": 0}, {"trial_index": 1}]},
                {"round": 1, "trials": [{"trial_index": 2}]},
            ],
        )
        assert result.details.get("rounds_completed") == 2
        assert result.details.get("trials_completed") == 3

    def test_stop_reason_details_are_populated(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.25,
            metric_name="accuracy",
            details={"stop_reason": "error", "error": "boom"},
        )
        stop_details = result.details.get("stop_reason_details") or {}
        assert stop_details.get("best_score") == 0.25
        assert stop_details.get("error") == (
            "An error occurred during optimization; see internal logs for details."
        )

    def test_creates_with_dict_of_prompts(self) -> None:
        prompts = {
            "main": ChatPrompt(system="Main", user="Query"),
            "helper": ChatPrompt(system="Helper", user="Task"),
        }
        result = OptimizationResult(
            prompt=prompts,
            score=0.9,
            metric_name="accuracy",
        )
        assert isinstance(result.prompt, dict)
        assert "main" in result.prompt


class TestOptimizationResultGetters:
    """Tests for OptimizationResult getter methods."""

    def test_get_run_link_returns_url(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            optimization_id="opt-123",
            dataset_id="ds-456",
        )
        link = result.get_run_link()
        assert isinstance(link, str)

    def test_get_optimized_model_kwargs_returns_dict(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            details={"optimized_model_kwargs": {"temperature": 0.7, "top_p": 0.9}},
        )
        kwargs = result.get_optimized_model_kwargs()
        assert kwargs == {"temperature": 0.7, "top_p": 0.9}

    def test_get_optimized_model_kwargs_returns_empty_when_missing(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
        )
        kwargs = result.get_optimized_model_kwargs()
        assert kwargs == {}

    def test_get_optimized_model_returns_model_name(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            details={"optimized_model": "gpt-4-turbo"},
        )
        model = result.get_optimized_model()
        assert model == "gpt-4-turbo"

    def test_get_optimized_model_returns_none_when_missing(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
        )
        model = result.get_optimized_model()
        assert model is None

    def test_get_optimized_parameters_returns_params(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            details={"optimized_parameters": {"temperature": 0.5, "max_tokens": 1000}},
        )
        params = result.get_optimized_parameters()
        assert params == {"temperature": 0.5, "max_tokens": 1000}

    def test_get_optimized_parameters_returns_empty_when_missing(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
        )
        params = result.get_optimized_parameters()
        assert params == {}


class TestRichRendering:
    def test_render_rich_result_returns_panel(self) -> None:
        prompt = ChatPrompt(system="Test", user="Query")
        result = OptimizationResult(
            optimizer="MetaPromptOptimizer",
            prompt=prompt,
            score=0.95,
            metric_name="f1_score",
            optimization_id="opt-123",
            dataset_id="ds-456",
            initial_prompt=prompt,
            initial_score=0.6,
            details={"rounds_completed": 1, "trials_completed": 1, "model": "gpt-4"},
            history=[],
        )

        panel = render_rich_result(result)
        import rich

        assert isinstance(panel, rich.panel.Panel)


class TestCalculateImprovementStr:
    """Tests for _calculate_improvement_str method."""

    def test_positive_improvement(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.90,
            metric_name="accuracy",
            initial_score=0.60,
        )
        improvement = result._calculate_improvement_str()
        assert "50.00%" in improvement
        assert "green" in improvement

    def test_negative_improvement(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.40,
            metric_name="accuracy",
            initial_score=0.60,
        )
        improvement = result._calculate_improvement_str()
        assert "-" in improvement
        assert "red" in improvement

    def test_no_initial_score(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
        )
        improvement = result._calculate_improvement_str()
        assert "N/A" in improvement

    def test_zero_initial_score_with_positive_final(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            initial_score=0.0,
        )
        improvement = result._calculate_improvement_str()
        assert "infinite" in improvement

    def test_zero_initial_and_final_scores(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.0,
            metric_name="accuracy",
            initial_score=0.0,
        )
        improvement = result._calculate_improvement_str()
        assert "0.00%" in improvement


class TestOptimizationResultStr:
    """Tests for __str__ method."""

    def test_str_contains_basic_info(self) -> None:
        result = OptimizationResult(
            optimizer="TestOptimizer",
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            details={"model": "gpt-4", "temperature": 0.7},
        )
        output = str(result)
        assert "OPTIMIZATION COMPLETE" in output
        assert "TestOptimizer" in output
        assert "accuracy" in output
        assert "0.8500" in output

    def test_str_contains_rounds_info(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            details={"trials_completed": 3, "rounds_completed": 3},
        )
        output = str(result)
        assert "Trials Completed: 3" in output

    def test_str_contains_parameter_summary(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            initial_score=0.60,
            details={
                "optimized_parameters": {"temperature": 0.7},
                "parameter_importance": {"temperature": 0.8},
                "search_ranges": {"stage1": {"temperature": {"min": 0.1, "max": 1.0}}},
            },
        )
        output = str(result)
        assert "Parameter Summary" in output
        assert "temperature" in output

    def test_str_strips_rich_formatting(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.90,
            metric_name="accuracy",
            initial_score=0.60,
        )
        output = str(result)
        # Rich formatting tags should be removed
        assert "[bold green]" not in output
        assert "[/bold green]" not in output


class TestOptimizationResultRich:
    """Tests for __rich__ method."""

    def test_rich_returns_panel(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
        )
        panel = result.__rich__()
        # Should return a rich Panel object
        assert panel is not None

    def test_rich_with_parameter_summary(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            initial_score=0.60,
            details={
                "optimized_parameters": {"temperature": 0.7, "top_p": 0.9},
                "parameter_importance": {"temperature": 0.5, "top_p": 0.3},
                "search_ranges": {
                    "coarse": {
                        "temperature": {"min": 0.0, "max": 1.0},
                        "top_p": {"min": 0.5, "max": 1.0},
                    }
                },
            },
        )
        panel = result.__rich__()
        assert panel is not None

    def test_rich_with_dict_prompt(self) -> None:
        result = OptimizationResult(
            prompt={
                "main": ChatPrompt(system="Main", user="Query"),
                "helper": ChatPrompt(system="Helper", user="Task"),
            },
            score=0.85,
            metric_name="accuracy",
        )
        panel = result.__rich__()
        assert panel is not None


class TestOptimizationResultDisplay:
    """Tests for display method."""

    def test_display_prints_output(self, capsys: pytest.CaptureFixture) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
        )
        with patch("opik_optimizer.optimization_result.get_console") as mock_console:
            mock_console.return_value = MagicMock()
            result.display()
            assert mock_console.return_value.print.call_count == 2

    def test_display_shows_link_when_available(
        self, capsys: pytest.CaptureFixture
    ) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            optimization_id="opt-123",
            dataset_id="ds-456",
        )
        with patch("opik_optimizer.optimization_result.get_console") as mock_console:
            mock_console.return_value = MagicMock()
            result.display()
            mock_console.return_value.print.assert_any_call(
                "Optimization run link: https://www.comet.com/opik/api/v1/session/redirect/optimizations/?optimization_id=opt-123&dataset_id=ds-123&path=a99999999999999999999999999999=="
            )

    def test_display_shows_no_link_message_when_missing(
        self, capsys: pytest.CaptureFixture
    ) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
        )
        with patch("opik_optimizer.optimization_result.get_console") as mock_console:
            mock_console.return_value = MagicMock()
            result.display()
            mock_console.return_value.print.assert_any_call(
                "Optimization run link: No optimization run link available",
                style="dim",
            )


class TestOptimizationResultModelDump:
    """Tests for model_dump method."""

    def test_model_dump_returns_dict(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
        )
        dumped = result.model_dump()
        assert isinstance(dumped, dict)
        assert dumped["score"] == 0.85
        assert dumped["metric_name"] == "accuracy"

    def test_model_dump_includes_all_fields(self) -> None:
        result = OptimizationResult(
            optimizer="TestOptimizer",
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            optimization_id="opt-123",
            llm_calls=50,
        )
        dumped = result.model_dump()
        assert dumped["optimizer"] == "TestOptimizer"
        assert dumped["optimization_id"] == "opt-123"
        assert dumped["llm_calls"] == 50


class TestOptimizationResultEdgeCases:
    """Tests for edge cases in OptimizationResult."""

    def test_handles_empty_details(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            details={},
        )
        output = str(result)
        assert "OPTIMIZATION COMPLETE" in output

    def test_handles_search_ranges_with_choices(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            details={
                "optimized_parameters": {"model": "gpt-4"},
                "search_ranges": {
                    "stage1": {"model": {"choices": ["gpt-3.5", "gpt-4"]}}
                },
            },
        )
        output = str(result)
        assert "model" in output

    def test_handles_search_stages(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(system="Test", user="Query"),
            score=0.85,
            metric_name="accuracy",
            details={
                "optimized_parameters": {"temperature": 0.5},
                "search_stages": [{"stage": "coarse"}, {"stage": "fine"}],
                "search_ranges": {
                    "coarse": {"temperature": {"min": 0.0, "max": 1.0}},
                    "fine": {"temperature": {"min": 0.3, "max": 0.7}},
                },
            },
        )
        output = str(result)
        assert "temperature" in output

    def test_handles_multimodal_prompt_in_rich(self) -> None:
        result = OptimizationResult(
            prompt=ChatPrompt(
                messages=[
                    {"role": "system", "content": "Analyze."},
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": "What is this?"},
                            {
                                "type": "image_url",
                                "image_url": {"url": "data:image/png;base64,abc"},
                            },
                        ],
                    },
                ]
            ),
            score=0.85,
            metric_name="accuracy",
        )
        panel = result.__rich__()
        assert panel is not None

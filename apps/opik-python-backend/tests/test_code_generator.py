"""Tests for OptimizationCodeGenerator in Optimization Studio."""

import json
import pytest
import tempfile
from pathlib import Path
from unittest.mock import MagicMock, patch
from uuid import uuid4

from opik_backend.studio.code_generator import (
    format_python_value,
    OptimizerCodeGenerator,
    MetricCodeGenerator,
    OptimizationCodeGenerator,
)
from opik_backend.studio.types import OptimizationConfig, OptimizationJobContext
from opik_backend.studio.exceptions import InvalidOptimizerError, InvalidMetricError


class TestFormatPythonValue:
    """Tests for format_python_value() helper function."""

    def test_format_none(self):
        """Test formatting None value."""
        assert format_python_value(None) == "None"

    def test_format_bool(self):
        """Test formatting boolean values."""
        assert format_python_value(True) == "True"
        assert format_python_value(False) == "False"

    def test_format_int(self):
        """Test formatting integer values."""
        assert format_python_value(42) == "42"
        assert format_python_value(0) == "0"
        assert format_python_value(-10) == "-10"

    def test_format_float(self):
        """Test formatting float values."""
        assert format_python_value(3.14) == "3.14"
        assert format_python_value(0.0) == "0.0"
        assert format_python_value(-1.5) == "-1.5"

    def test_format_string(self):
        """Test formatting string values with escaping."""
        assert format_python_value("hello") == '"hello"'
        assert format_python_value('say "hello"') == '"say \\"hello\\""'
        assert format_python_value("line1\nline2") == '"line1\\nline2"'
        assert format_python_value("back\\slash") == '"back\\\\slash"'

    def test_format_empty_dict(self):
        """Test formatting empty dictionary."""
        assert format_python_value({}) == "{}"

    def test_format_dict(self):
        """Test formatting dictionary."""
        result = format_python_value({"a": 1, "b": "test"})
        # Should contain both key-value pairs
        assert '"a": 1' in result or "'a': 1" in result
        assert '"b": "test"' in result or "'b': \"test\"" in result

    def test_format_nested_dict(self):
        """Test formatting nested dictionary."""
        value = {"outer": {"inner": 42}}
        result = format_python_value(value)
        assert "outer" in result
        assert "inner" in result
        assert "42" in result

    def test_format_empty_list(self):
        """Test formatting empty list."""
        assert format_python_value([]) == "[]"

    def test_format_list(self):
        """Test formatting list."""
        result = format_python_value([1, 2, 3])
        assert "1" in result
        assert "2" in result
        assert "3" in result

    def test_format_list_with_strings(self):
        """Test formatting list with strings."""
        result = format_python_value(["a", "b", "c"])
        assert '"a"' in result
        assert '"b"' in result
        assert '"c"' in result

    def test_format_nested_list(self):
        """Test formatting nested list."""
        value = [[1, 2], [3, 4]]
        result = format_python_value(value)
        assert "1" in result
        assert "2" in result
        assert "3" in result
        assert "4" in result


class TestOptimizerCodeGenerator:
    """Tests for OptimizerCodeGenerator."""

    def test_generate_unknown_optimizer_raises_error(self):
        """Test that generating code for unknown optimizer raises error."""
        config = OptimizationConfig(
            dataset_name="test-dataset",
            prompt_messages=[{"role": "user", "content": "test"}],
            model="openai/gpt-4o",
            model_params={},
            metric_type="equals",
            metric_params={},
            optimizer_type="unknown_optimizer",
            optimizer_params={},
        )

        with pytest.raises(InvalidOptimizerError):
            OptimizerCodeGenerator.generate("unknown_optimizer", config)

    def test_generate_gepa_optimizer(self):
        """Test generating code for GEPA optimizer."""
        config = OptimizationConfig(
            dataset_name="test-dataset",
            prompt_messages=[{"role": "user", "content": "test"}],
            model="openai/gpt-4o-mini",
            model_params={"temperature": 0.0},
            metric_type="equals",
            metric_params={},
            optimizer_type="gepa",
            optimizer_params={"n_threads": 8, "verbose": 1},
        )

        code = OptimizerCodeGenerator.generate("gepa", config)

        assert "GepaOptimizer" in code
        assert "model=" in code
        assert "openai/gpt-4o-mini" in code
        assert "n_threads=" in code
        assert "8" in code

    def test_generate_evolutionary_optimizer(self):
        """Test generating code for Evolutionary optimizer."""
        config = OptimizationConfig(
            dataset_name="test-dataset",
            prompt_messages=[{"role": "user", "content": "test"}],
            model="openai/gpt-4o",
            model_params={},
            metric_type="equals",
            metric_params={},
            optimizer_type="evolutionary",
            optimizer_params={"population_size": 10},
        )

        code = OptimizerCodeGenerator.generate("evolutionary", config)

        assert "EvolutionaryOptimizer" in code
        assert "model=" in code
        assert "population_size=" in code

    def test_generate_hierarchical_reflective_optimizer(self):
        """Test generating code for Hierarchical Reflective optimizer."""
        config = OptimizationConfig(
            dataset_name="test-dataset",
            prompt_messages=[{"role": "user", "content": "test"}],
            model="openai/gpt-4o",
            model_params={},
            metric_type="equals",
            metric_params={},
            optimizer_type="hierarchical_reflective",
            optimizer_params={},
        )

        code = OptimizerCodeGenerator.generate("hierarchical_reflective", config)

        assert "HierarchicalReflectiveOptimizer" in code
        assert "model=" in code

    def test_generate_optimizer_with_empty_params(self):
        """Test generating code for optimizer with empty parameters."""
        config = OptimizationConfig(
            dataset_name="test-dataset",
            prompt_messages=[{"role": "user", "content": "test"}],
            model="openai/gpt-4o",
            model_params={},
            metric_type="equals",
            metric_params={},
            optimizer_type="gepa",
            optimizer_params={},
        )

        code = OptimizerCodeGenerator.generate("gepa", config)

        assert "GepaOptimizer" in code
        assert "model=" in code

    def test_generate_optimizer_with_model_params(self):
        """Test generating code for optimizer with model parameters."""
        config = OptimizationConfig(
            dataset_name="test-dataset",
            prompt_messages=[{"role": "user", "content": "test"}],
            model="openai/gpt-4o",
            model_params={"temperature": 0.7, "max_tokens": 1000},
            metric_type="equals",
            metric_params={},
            optimizer_type="gepa",
            optimizer_params={},
        )

        code = OptimizerCodeGenerator.generate("gepa", config)

        assert "model_parameters=" in code
        assert "temperature" in code
        assert "max_tokens" in code


class TestMetricCodeGenerator:
    """Tests for MetricCodeGenerator."""

    def test_generate_unknown_metric_raises_error(self):
        """Test that generating code for unknown metric raises error."""
        config = OptimizationConfig(
            dataset_name="test-dataset",
            prompt_messages=[{"role": "user", "content": "test"}],
            model="openai/gpt-4o",
            model_params={},
            metric_type="unknown_metric",
            metric_params={},
            optimizer_type="gepa",
            optimizer_params={},
        )

        with pytest.raises(InvalidMetricError):
            MetricCodeGenerator.generate("unknown_metric", config)

    def test_generate_equals_metric(self):
        """Test generating code for equals metric."""
        config = OptimizationConfig(
            dataset_name="test-dataset",
            prompt_messages=[{"role": "user", "content": "test"}],
            model="openai/gpt-4o",
            model_params={},
            metric_type="equals",
            metric_params={"case_sensitive": False, "reference_key": "answer"},
            optimizer_type="gepa",
            optimizer_params={},
        )

        code = MetricCodeGenerator.generate("equals", config)

        assert "def metric_fn" in code
        assert "Equals" in code
        assert "reference" in code
        assert "output" in code
        assert "dataset_item.get" in code

    def test_generate_levenshtein_ratio_metric(self):
        """Test generating code for levenshtein_ratio metric."""
        config = OptimizationConfig(
            dataset_name="test-dataset",
            prompt_messages=[{"role": "user", "content": "test"}],
            model="openai/gpt-4o",
            model_params={},
            metric_type="levenshtein_ratio",
            metric_params={"case_sensitive": True},
            optimizer_type="gepa",
            optimizer_params={},
        )

        code = MetricCodeGenerator.generate("levenshtein_ratio", config)

        assert "def metric_fn" in code
        assert "LevenshteinRatio" in code
        assert "reference" in code
        assert "output" in code

    def test_generate_geval_metric(self):
        """Test generating code for geval metric."""
        config = OptimizationConfig(
            dataset_name="test-dataset",
            prompt_messages=[{"role": "user", "content": "test"}],
            model="openai/gpt-4o",
            model_params={},
            metric_type="geval",
            metric_params={
                "task_introduction": "Evaluate quality",
                "evaluation_criteria": "Is it helpful?",
            },
            optimizer_type="gepa",
            optimizer_params={},
        )

        code = MetricCodeGenerator.generate("geval", config)

        assert "def metric_fn" in code
        assert "GEval" in code
        assert "input=" in code
        assert "output=" in code
        assert "task_introduction" in code
        assert "evaluation_criteria" in code

    def test_generate_json_schema_validator_metric(self):
        """Test generating code for json_schema_validator metric."""
        config = OptimizationConfig(
            dataset_name="test-dataset",
            prompt_messages=[{"role": "user", "content": "test"}],
            model="openai/gpt-4o",
            model_params={},
            metric_type="json_schema_validator",
            metric_params={"schema_key": "my_schema"},
            optimizer_type="gepa",
            optimizer_params={},
        )

        code = MetricCodeGenerator.generate("json_schema_validator", config)

        assert "def metric_fn" in code
        assert "StructuredOutputCompliance" in code
        assert "schema" in code
        assert "my_schema" in code


class TestOptimizationCodeGenerator:
    """Tests for OptimizationCodeGenerator.generate()."""

    def _create_test_config(self, **overrides):
        """Helper to create test OptimizationConfig."""
        defaults = {
            "dataset_name": "test-dataset",
            "prompt_messages": [{"role": "user", "content": "Answer: {question}"}],
            "model": "openai/gpt-4o-mini",
            "model_params": {"temperature": 0.0},
            "metric_type": "equals",
            "metric_params": {"reference_key": "answer"},
            "optimizer_type": "gepa",
            "optimizer_params": {"n_threads": 8},
        }
        defaults.update(overrides)
        return OptimizationConfig(**defaults)

    def _create_test_context(self):
        """Helper to create test OptimizationJobContext."""
        return OptimizationJobContext(
            optimization_id=str(uuid4()),
            workspace_id="workspace-123",
            workspace_name="test-workspace",
            config={},
            opik_api_key=None,
        )

    def test_generate_complete_code(self):
        """Test generating complete optimization code."""
        config = self._create_test_config()
        context = self._create_test_context()

        code = OptimizationCodeGenerator.generate(config, context)

        # Check imports
        assert "import opik" in code
        assert "from opik_optimizer import ChatPrompt" in code
        assert "GepaOptimizer" in code
        assert "from opik.evaluation.metrics import Equals" in code

        # Check dataset loading
        assert "client.get_dataset" in code
        assert "test-dataset" in code

        # Check prompt creation
        assert "ChatPrompt(messages=" in code

        # Check metric function
        assert "def metric_fn" in code

        # Check optimizer instantiation
        assert "optimizer = GepaOptimizer" in code

        # Check optimization call
        assert "optimizer.optimize_prompt" in code
        assert "optimization_id=" in code

        # Check output
        assert "json.dumps(output)" in code
        assert "success" in code
        assert "score" in code

    def test_generate_code_with_different_optimizer(self):
        """Test generating code with different optimizer type."""
        config = self._create_test_config(optimizer_type="evolutionary")
        context = self._create_test_context()

        code = OptimizationCodeGenerator.generate(config, context)

        assert "EvolutionaryOptimizer" in code
        assert "GepaOptimizer" not in code

    def test_generate_code_with_different_metric(self):
        """Test generating code with different metric type."""
        config = self._create_test_config(
            metric_type="geval",
            metric_params={
                "task_introduction": "Evaluate",
                "evaluation_criteria": "Criteria",
            },
        )
        context = self._create_test_context()

        code = OptimizationCodeGenerator.generate(config, context)

        assert "GEval" in code
        assert "Equals" not in code

    def test_generate_code_with_complex_prompt_messages(self):
        """Test generating code with multiple prompt messages."""
        config = self._create_test_config(
            prompt_messages=[
                {"role": "system", "content": "You are helpful."},
                {"role": "user", "content": "Answer: {question}"},
            ]
        )
        context = self._create_test_context()

        code = OptimizationCodeGenerator.generate(config, context)

        assert "ChatPrompt(messages=" in code
        # Should contain both messages
        assert "system" in code
        assert "user" in code

    def test_generate_code_with_special_characters(self):
        """Test generating code with special characters in strings."""
        config = self._create_test_config(
            prompt_messages=[{"role": "user", "content": 'Say "hello" and\nnewline'}]
        )
        context = self._create_test_context()

        code = OptimizationCodeGenerator.generate(config, context)

        # Should properly escape quotes and newlines
        assert '\\"' in code or '\\"' in code
        assert "\\n" in code

    def test_generate_code_includes_runtime_params(self):
        """Test that generated code includes runtime parameters."""
        config = self._create_test_config()
        context = self._create_test_context()

        code = OptimizationCodeGenerator.generate(config, context)

        # Should include runtime params as keyword arguments
        assert "max_trials" in code
        assert "n_samples" in code
        assert "reflection_minibatch_size" in code
        assert "candidate_selection_strategy" in code
        assert "max_retries" in code

    def test_generate_code_outputs_json(self):
        """Test that generated code outputs JSON result (server-side)."""
        config = self._create_test_config()
        context = self._create_test_context()

        code = OptimizationCodeGenerator.generate(
            config, context, for_user_download=False
        )

        # Should output JSON on last line
        assert "print(json.dumps(output))" in code
        assert "optimization_id" in code
        assert "score" in code
        assert "initial_score" in code

    def test_generate_code_for_user_download(self):
        """Test that generated code for user download has correct format."""
        config = self._create_test_config()
        context = self._create_test_context()

        code = OptimizationCodeGenerator.generate(
            config, context, for_user_download=True
        )

        # Should have API key setup instructions
        assert "# Configure the SDK" in code
        assert 'os.environ["OPIK_API_KEY"]' in code
        assert "# Set your Opik API key" in code

        # Should NOT have stdin reading
        assert "sys.stdin.read()" not in code
        assert "json.loads(sys.stdin.read())" not in code

        # Should use result.display() instead of JSON output
        assert "result.display()" in code
        assert "print(json.dumps(output))" not in code

        # Should NOT include optimization_id in optimize_prompt call
        assert (
            "optimization_id=" not in code or code.count("optimization_id=") == 1
        )  # Only in comment or output dict, not in call

        # Should have the comment about metadata logging
        assert "# Optimizer metadata" in code

    def test_generate_code_server_side_default(self):
        """Test that default behavior is server-side code generation."""
        config = self._create_test_config()
        context = self._create_test_context()

        # Default (no flag) should be server-side
        code_default = OptimizationCodeGenerator.generate(config, context)
        code_server = OptimizationCodeGenerator.generate(
            config, context, for_user_download=False
        )

        # Should be identical
        assert code_default == code_server

        # Should have stdin reading
        assert "sys.stdin.read()" in code_default
        assert "print(json.dumps(output))" in code_default


class TestCodeGeneratorIntegration:
    """Integration tests for code generation and execution."""

    def test_generated_code_is_valid_python(self):
        """Test that generated code is syntactically valid Python."""
        from opik_backend.studio.code_generator import OptimizationCodeGenerator
        from opik_backend.studio.types import OptimizationConfig, OptimizationJobContext
        from uuid import uuid4

        config = OptimizationConfig(
            dataset_name="test-dataset",
            prompt_messages=[{"role": "user", "content": "test"}],
            model="openai/gpt-4o-mini",
            model_params={},
            metric_type="equals",
            metric_params={},
            optimizer_type="gepa",
            optimizer_params={},
        )

        context = OptimizationJobContext(
            optimization_id=str(uuid4()),
            workspace_id="workspace-123",
            workspace_name="test-workspace",
            config={},
            opik_api_key=None,
        )

        code = OptimizationCodeGenerator.generate(config, context)

        # Try to compile the code to check syntax
        compile(code, "<string>", "exec")

    def test_generated_code_syntax_all_optimizers(self):
        """Test that generated code for all optimizer types is syntactically valid."""
        import ast
        from opik_backend.studio.code_generator import OptimizationCodeGenerator
        from opik_backend.studio.types import OptimizationConfig, OptimizationJobContext
        from uuid import uuid4

        optimizer_types = ["gepa", "evolutionary", "hierarchical_reflective"]

        for optimizer_type in optimizer_types:
            config = OptimizationConfig(
                dataset_name="test-dataset",
                prompt_messages=[{"role": "user", "content": "test"}],
                model="openai/gpt-4o-mini",
                model_params={"temperature": 0.0},
                metric_type="equals",
                metric_params={},
                optimizer_type=optimizer_type,
                optimizer_params={"n_threads": 8},
            )

            context = OptimizationJobContext(
                optimization_id=str(uuid4()),
                workspace_id="workspace-123",
                workspace_name="test-workspace",
                config={},
                opik_api_key=None,
            )

            # Test both server-side and user download code
            for for_user_download in [False, True]:
                code = OptimizationCodeGenerator.generate(
                    config, context, for_user_download=for_user_download
                )

                # Parse with AST to validate syntax
                try:
                    ast.parse(code)
                except SyntaxError as e:
                    mode = "user download" if for_user_download else "server-side"
                    pytest.fail(
                        f"Generated code for {optimizer_type} optimizer ({mode}) has syntax error: {e}"
                    )

    def test_generated_code_syntax_all_metrics(self):
        """Test that generated code for all metric types is syntactically valid."""
        import ast
        from opik_backend.studio.code_generator import OptimizationCodeGenerator
        from opik_backend.studio.types import OptimizationConfig, OptimizationJobContext
        from uuid import uuid4

        metric_configs = [
            ("equals", {"reference_key": "answer"}),
            ("levenshtein_ratio", {"case_sensitive": False}),
            (
                "geval",
                {
                    "task_introduction": "Evaluate quality",
                    "evaluation_criteria": "Is it helpful?",
                },
            ),
            ("json_schema_validator", {"schema_key": "my_schema"}),
        ]

        for metric_type, metric_params in metric_configs:
            config = OptimizationConfig(
                dataset_name="test-dataset",
                prompt_messages=[{"role": "user", "content": "test"}],
                model="openai/gpt-4o-mini",
                model_params={},
                metric_type=metric_type,
                metric_params=metric_params,
                optimizer_type="gepa",
                optimizer_params={},
            )

            context = OptimizationJobContext(
                optimization_id=str(uuid4()),
                workspace_id="workspace-123",
                workspace_name="test-workspace",
                config={},
                opik_api_key=None,
            )

            # Test both server-side and user download code
            for for_user_download in [False, True]:
                code = OptimizationCodeGenerator.generate(
                    config, context, for_user_download=for_user_download
                )

                # Parse with AST to validate syntax
                try:
                    ast.parse(code)
                except SyntaxError as e:
                    mode = "user download" if for_user_download else "server-side"
                    pytest.fail(
                        f"Generated code for {metric_type} metric ({mode}) has syntax error: {e}"
                    )

    def test_generated_code_syntax_with_complex_params(self):
        """Test that generated code with complex parameters is syntactically valid."""
        import ast
        from opik_backend.studio.code_generator import OptimizationCodeGenerator
        from opik_backend.studio.types import OptimizationConfig, OptimizationJobContext
        from uuid import uuid4

        config = OptimizationConfig(
            dataset_name="test-dataset",
            prompt_messages=[
                {"role": "system", "content": 'Say "hello" with\nnewlines'},
                {"role": "user", "content": "Answer: {question}"},
            ],
            model="openai/gpt-4o-mini",
            model_params={
                "temperature": 0.7,
                "max_tokens": 1000,
                "top_p": 0.9,
            },
            metric_type="geval",
            metric_params={
                "task_introduction": "Evaluate the response",
                "evaluation_criteria": "Check if it's helpful and accurate",
            },
            optimizer_type="evolutionary",
            optimizer_params={
                "population_size": 10,
                "num_generations": 5,
                "mutation_rate": 0.2,
            },
        )

        context = OptimizationJobContext(
            optimization_id=str(uuid4()),
            workspace_id="workspace-123",
            workspace_name="test-workspace",
            config={},
            opik_api_key=None,
        )

        code = OptimizationCodeGenerator.generate(config, context)

        # Parse with AST to validate syntax
        try:
            tree = ast.parse(code)
            # Additional validation: check that the AST is well-formed
            assert isinstance(tree, ast.Module)
        except SyntaxError as e:
            pytest.fail(f"Generated code with complex parameters has syntax error: {e}")

    def test_generated_code_can_be_written_to_file(self):
        """Test that generated code can be written to a file and is valid Python."""
        import ast
        from opik_backend.studio.code_generator import OptimizationCodeGenerator
        from opik_backend.studio.types import OptimizationConfig, OptimizationJobContext
        from uuid import uuid4

        config = OptimizationConfig(
            dataset_name="test-dataset",
            prompt_messages=[{"role": "user", "content": "Answer: {question}"}],
            model="openai/gpt-4o-mini",
            model_params={"temperature": 0.0},
            metric_type="equals",
            metric_params={"reference_key": "answer"},
            optimizer_type="gepa",
            optimizer_params={"n_threads": 8},
        )

        context = OptimizationJobContext(
            optimization_id=str(uuid4()),
            workspace_id="workspace-123",
            workspace_name="test-workspace",
            config={},
            opik_api_key=None,
        )

        code = OptimizationCodeGenerator.generate(config, context)

        # Write to temporary file
        with tempfile.NamedTemporaryFile(mode="w", suffix=".py", delete=False) as f:
            f.write(code)
            temp_file = f.name

        try:
            # Read back and verify it's still valid
            with open(temp_file, "r") as f:
                read_code = f.read()

            assert read_code == code

            # Validate syntax by parsing
            ast.parse(read_code)

            # Verify structure
            assert "import json" in read_code
            assert "import opik" in read_code
            assert "def metric_fn" in read_code
            assert "optimizer.optimize_prompt" in read_code
        finally:
            Path(temp_file).unlink()

    def test_generated_code_ast_structure(self):
        """Test that generated code has correct AST structure."""
        import ast
        from opik_backend.studio.code_generator import OptimizationCodeGenerator
        from opik_backend.studio.types import OptimizationConfig, OptimizationJobContext
        from uuid import uuid4

        config = OptimizationConfig(
            dataset_name="test-dataset",
            prompt_messages=[{"role": "user", "content": "test"}],
            model="openai/gpt-4o-mini",
            model_params={},
            metric_type="equals",
            metric_params={},
            optimizer_type="gepa",
            optimizer_params={},
        )

        context = OptimizationJobContext(
            optimization_id=str(uuid4()),
            workspace_id="workspace-123",
            workspace_name="test-workspace",
            config={},
            opik_api_key=None,
        )

        code = OptimizationCodeGenerator.generate(config, context)

        # Parse and validate AST structure
        tree = ast.parse(code)

        # Check that we have imports
        imports = [
            node for node in tree.body if isinstance(node, (ast.Import, ast.ImportFrom))
        ]
        assert len(imports) > 0, "Generated code should have imports"

        # Check that we have function definitions
        functions = [node for node in tree.body if isinstance(node, ast.FunctionDef)]
        assert len(functions) > 0, "Generated code should have function definitions"

        # Check that we have assignments (for optimizer, dataset, etc.)
        assignments = [node for node in tree.body if isinstance(node, ast.Assign)]
        assert len(assignments) > 0, "Generated code should have variable assignments"

        # Check that we have a print statement or expression
        prints = [
            node
            for node in ast.walk(tree)
            if isinstance(node, ast.Call)
            and isinstance(node.func, ast.Name)
            and node.func.id == "print"
        ]
        assert (
            len(prints) > 0
        ), "Generated code should have a print statement for output"

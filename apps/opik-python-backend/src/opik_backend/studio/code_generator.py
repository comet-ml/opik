"""Code generator for Optimization Studio.

This module generates Python code strings from optimization configurations.
Uses introspection to automatically discover parameters from optimizer/metric classes,
making it easy to add new algorithms and parameters without code changes.
"""

import inspect
import json
import logging
from typing import Any, Callable, Dict, Optional, Type

from .config import OPTIMIZER_RUNTIME_PARAMS
from .exceptions import InvalidMetricError, InvalidOptimizerError
from .metrics import MetricFactory
from .optimizers import OptimizerFactory
from .types import OptimizationConfig, OptimizationJobContext

logger = logging.getLogger(__name__)

# Map metric types to their classes and score method signatures
# Format: (class_name, score_param1, score_param2)
METRIC_CLASS_MAP = {
    "equals": ("Equals", "reference", "output"),
    "levenshtein_ratio": ("LevenshteinRatio", "reference", "output"),
    "geval": ("GEval", "input", "output"),
    "json_schema_validator": ("StructuredOutputCompliance", "output", "schema"),
}

# Code templates for different execution contexts
USER_CODE_TEMPLATE = """import warnings
# Suppress Pydantic serialization warnings
warnings.filterwarnings(
    "ignore",
    message=".*Pydantic serializer warnings.*",
    category=UserWarning,
)
warnings.filterwarnings(
    "ignore",
    message=".*PydanticSerializationUnexpectedValue.*",
    category=UserWarning,
)

# Configure the SDK
import os
# Set your Opik API key
# os.environ["OPIK_API_KEY"] = "your-api-key-here"

import opik
from opik_optimizer import ChatPrompt, {optimizer_class_name}
from opik.evaluation.metrics import {metric_class_name}

# Initialize client
client = opik.Opik()

# Load dataset
dataset = client.get_dataset(name={dataset_name})

# Create prompt
prompt = ChatPrompt(messages={prompt_messages})

# Define metric function
{metric_code}

# Create optimizer
{optimizer_code}

# Run optimization
result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=metric_fn,
{runtime_params}
)

result.display()
# Optimizer metadata (prompt, tools, version) is logged automatically.
"""

RUN_CODE_TEMPLATE = """import json
import sys
import os
import warnings

# Suppress Pydantic serialization warnings
warnings.filterwarnings(
    "ignore",
    message=".*Pydantic serializer warnings.*",
    category=UserWarning,
)
warnings.filterwarnings(
    "ignore",
    message=".*PydanticSerializationUnexpectedValue.*",
    category=UserWarning,
)

# Environment setup (already set by parent process)
# OPIK_API_KEY and OPIK_WORKSPACE are set via env_vars

import opik
from opik_optimizer import ChatPrompt, {optimizer_class_name}
from opik.evaluation.metrics import {metric_class_name}

# Read input from stdin (for consistency with other executors)
input_data = json.loads(sys.stdin.read())
job_message = input_data.get("data", {{}})

# Initialize client
client = opik.Opik()

# Load dataset
dataset = client.get_dataset(name={dataset_name})

# Create prompt
prompt = ChatPrompt(messages={prompt_messages})

# Define metric function
{metric_code}

# Create optimizer
{optimizer_code}

# Run optimization
result = optimizer.optimize_prompt(
    optimization_id={optimization_id},
    prompt=prompt,
    dataset=dataset,
    metric=metric_fn,
{runtime_params}
)

# Output result as JSON
output = {{
    "success": True,
    "optimization_id": {optimization_id},
    "score": result.score,
    "initial_score": result.initial_score,
}}
if hasattr(result, "prompt") and result.prompt:
    if hasattr(result.prompt, "messages"):
        output["optimized_prompt"] = result.prompt.messages
    elif isinstance(result.prompt, list):
        output["optimized_prompt"] = result.prompt

print(json.dumps(output))
"""


def format_python_value(value: Any, indent: int = 0) -> str:
    """Convert Python value to valid Python code string representation.

    Handles: None, bool, int, float, str, dict, list, nested structures.
    Properly escapes strings, formats dicts/lists with indentation.

    Args:
        value: Python value to format
        indent: Current indentation level (for nested structures)

    Returns:
        Python code string representation of the value

    Examples:
        >>> format_python_value("hello")
        '"hello"'
        >>> format_python_value({"a": 1, "b": "test"})
        '{"a": 1, "b": "test"}'
        >>> format_python_value([1, 2, 3])
        '[1, 2, 3]'
    """
    indent_str = "    " * indent

    if value is None:
        return "None"
    elif isinstance(value, bool):
        return "True" if value else "False"
    elif isinstance(value, int):
        return str(value)
    elif isinstance(value, float):
        return str(value)
    elif isinstance(value, str):
        # Escape quotes and newlines
        escaped = (
            value.replace("\\", "\\\\")
            .replace('"', '\\"')
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        )
        return f'"{escaped}"'
    elif isinstance(value, dict):
        if not value:
            return "{}"
        items = []
        for k, v in value.items():
            key_str = (
                format_python_value(k, indent + 1) if isinstance(k, str) else str(k)
            )
            val_str = format_python_value(v, indent + 1)
            items.append(f"{key_str}: {val_str}")
        items_str = ",\n".join(f"{indent_str}    {item}" for item in items)
        return f"{{\n{items_str}\n{indent_str}}}"
    elif isinstance(value, list):
        if not value:
            return "[]"
        items = []
        for item in value:
            items.append(format_python_value(item, indent + 1))
        items_str = ",\n".join(f"{indent_str}    {item}" for item in items)
        return f"[\n{items_str}\n{indent_str}]"
    else:
        # Fallback: use repr for unknown types
        return repr(value)


class OptimizerCodeGenerator:
    """Registry-based code generator for optimizer instantiation.

    Uses introspection to automatically generate code from optimizer class signatures.
    Custom generators can be registered via decorator for special cases.
    """

    _GENERATORS: Dict[str, Callable[[OptimizationConfig], str]] = {}

    @classmethod
    def register(cls, optimizer_type: str):
        """Decorator to register custom code generators for optimizer types.

        Args:
            optimizer_type: The optimizer type identifier (e.g., "gepa", "evolutionary")

        Returns:
            Decorator function

        Example:
            @OptimizerCodeGenerator.register("gepa")
            def _generate_gepa_code(config: OptimizationConfig) -> str:
                return "optimizer = GepaOptimizer(...)"
        """

        def decorator(func: Callable[[OptimizationConfig], str]):
            cls._GENERATORS[optimizer_type] = func
            return func

        return decorator

    @classmethod
    def generate(cls, optimizer_type: str, config: OptimizationConfig) -> str:
        """Generate Python code for optimizer instantiation.

        Args:
            optimizer_type: Type of optimizer (e.g., "gepa", "evolutionary")
            config: Optimization configuration

        Returns:
            Python code string for optimizer instantiation

        Raises:
            InvalidOptimizerError: If optimizer_type is not recognized
        """
        optimizer_type = optimizer_type.lower()

        # Check for custom generator first
        if optimizer_type in cls._GENERATORS:
            return cls._GENERATORS[optimizer_type](config)

        # Fallback to introspection-based generation
        return cls._generate_from_introspection(optimizer_type, config)

    @classmethod
    def _generate_from_introspection(
        cls, optimizer_type: str, config: OptimizationConfig
    ) -> str:
        """Generate optimizer code using introspection of the optimizer class.

        Args:
            optimizer_type: Type of optimizer
            config: Optimization configuration

        Returns:
            Python code string for optimizer instantiation
        """
        if optimizer_type not in OptimizerFactory._OPTIMIZERS:
            available = ", ".join(sorted(OptimizerFactory._OPTIMIZERS.keys()))
            raise InvalidOptimizerError(
                optimizer_type, f"Available optimizers: {available}"
            )

        optimizer_class = OptimizerFactory._OPTIMIZERS[optimizer_type]

        # Get class name for import
        class_name = optimizer_class.__name__

        # Get module path for import
        module_path = optimizer_class.__module__

        # Introspect __init__ signature
        sig = inspect.signature(optimizer_class.__init__)

        # Build parameter dict
        params = {}

        # Add model parameter
        params["model"] = format_python_value(config.model)

        # Add model_parameters if present
        if config.model_params:
            params["model_parameters"] = format_python_value(config.model_params)

        # Add optimizer-specific parameters
        if config.optimizer_params:
            for key, value in config.optimizer_params.items():
                # Skip parameters that are handled specially (model, model_parameters)
                # Also skip verbose - it belongs in optimize_prompt() call, not optimizer constructor
                if key not in ("model", "model_parameters", "verbose"):
                    params[key] = format_python_value(value)

        # Format parameters as keyword arguments
        param_strs = []
        for key, value_str in params.items():
            param_strs.append(f"{key}={value_str}")

        if param_strs:
            params_code = ",\n    ".join(param_strs)
            return f"""optimizer = {class_name}(
    {params_code}
)"""
        else:
            return f"""optimizer = {class_name}()"""


class MetricCodeGenerator:
    """Registry-based code generator for metric function definitions.

    Uses introspection to automatically generate code from metric class signatures.
    Custom generators can be registered via decorator for special cases.
    """

    _GENERATORS: Dict[str, Callable[[OptimizationConfig], str]] = {}

    @classmethod
    def register(cls, metric_type: str):
        """Decorator to register custom code generators for metric types.

        Args:
            metric_type: The metric type identifier (e.g., "equals", "geval")

        Returns:
            Decorator function
        """

        def decorator(func: Callable[[OptimizationConfig], str]):
            cls._GENERATORS[metric_type] = func
            return func

        return decorator

    @classmethod
    def generate(cls, metric_type: str, config: OptimizationConfig) -> str:
        """Generate Python code for metric function definition.

        Args:
            metric_type: Type of metric (e.g., "equals", "geval")
            config: Optimization configuration

        Returns:
            Python code string for metric function definition

        Raises:
            InvalidMetricError: If metric_type is not recognized
        """
        metric_type = metric_type.lower()

        # Check for custom generator first
        if metric_type in cls._GENERATORS:
            return cls._GENERATORS[metric_type](config)

        # Fallback to introspection-based generation
        return cls._generate_from_introspection(metric_type, config)

    @classmethod
    def _generate_from_introspection(
        cls, metric_type: str, config: OptimizationConfig
    ) -> str:
        """Generate metric code using introspection of the metric class.

        Args:
            metric_type: Type of metric
            config: Optimization configuration

        Returns:
            Python code string for metric function definition
        """
        if metric_type not in MetricFactory._BUILDERS:
            available = ", ".join(sorted(MetricFactory._BUILDERS.keys()))
            raise InvalidMetricError(metric_type, f"Available metrics: {available}")

        # Get the metric builder to understand what metric class it uses
        # We need to look at the actual metric classes used by the builders
        metric_params = config.metric_params or {}

        if metric_type not in METRIC_CLASS_MAP:
            # Generic fallback
            return cls._generate_generic_metric(metric_type, config)

        class_name, score_param1, score_param2 = METRIC_CLASS_MAP[metric_type]

        # Build metric instantiation parameters
        # For GEval, we need task_introduction and evaluation_criteria
        if metric_type == "geval":
            geval_params = {}
            if "task_introduction" in metric_params:
                geval_params["task_introduction"] = metric_params["task_introduction"]
            if "evaluation_criteria" in metric_params:
                geval_params["evaluation_criteria"] = metric_params[
                    "evaluation_criteria"
                ]
            if config.model:
                geval_params["model"] = config.model
            metric_params_code = (
                format_python_value(geval_params) if geval_params else ""
            )
        else:
            metric_params_code = (
                format_python_value(metric_params) if metric_params else ""
            )

        # Determine score method parameters based on metric type
        if metric_type == "geval":
            score_code = f"""    return metric.score(
        input=dataset_item,
        output=llm_output
    )"""
        elif metric_type == "json_schema_validator":
            schema_key = metric_params.get("schema_key", "json_schema")
            score_code = f"""    schema = dataset_item.get({format_python_value(schema_key)})
    if not schema:
        from opik.evaluation.metrics.score_result import ScoreResult
        return ScoreResult(
            value=0.0,
            name="json_schema_validator",
            reason=f"Missing schema in dataset item key '{schema_key}'"
        )
    return metric.score(
        output=llm_output,
        schema=schema
    )"""
        else:
            # equals, levenshtein_ratio
            reference_key = metric_params.get("reference_key", "answer")
            score_code = f"""    reference = dataset_item.get({format_python_value(reference_key)}, "")
    return metric.score(
        reference=reference,
        output=llm_output
    )"""

        # Format metric instantiation - handle empty params
        if metric_params_code:
            metric_instantiation = f"metric = {class_name}({metric_params_code})"
        else:
            metric_instantiation = f"metric = {class_name}()"

        return f"""def metric_fn(dataset_item, llm_output):
    {metric_instantiation}
{score_code}"""

    @classmethod
    def _generate_generic_metric(
        cls, metric_type: str, config: OptimizationConfig
    ) -> str:
        """Generate generic metric function code as fallback.

        Args:
            metric_type: Type of metric
            config: Optimization configuration

        Returns:
            Python code string for metric function definition
        """
        metric_params = config.metric_params or {}
        metric_params_code = format_python_value(metric_params)

        return f"""def metric_fn(dataset_item, llm_output):
    # Generic metric implementation for {metric_type}
    # Parameters: {metric_params_code}
    # TODO: Implement custom metric logic
    from opik.evaluation.metrics.score_result import ScoreResult
    return ScoreResult(value=0.0, name="{metric_type}", reason="Not implemented")
"""


class OptimizationCodeGenerator:
    """Main code generator for Optimization Studio jobs.

    Generates complete Python code that can be executed to run an optimization.
    Uses registry-based generators with introspection fallback for maintainability.
    """

    @classmethod
    def generate(
        cls,
        config: OptimizationConfig,
        context: OptimizationJobContext,
        for_user_download: bool = False,
    ) -> str:
        """Generate Python code string for optimization execution.

        Args:
            config: Optimization configuration
            context: Job context with optimization ID and workspace info
            for_user_download: If True, generates code for user download (with API key setup
                              and result.display()). If False, generates code for server execution
                              (with stdin reading and JSON output). Defaults to False.

        Returns:
            Complete Python code string ready for execution or download
        """
        # Get optimizer class name for import
        optimizer_type = config.optimizer_type.lower()
        if optimizer_type not in OptimizerFactory._OPTIMIZERS:
            raise InvalidOptimizerError(optimizer_type, "Unknown optimizer type")

        optimizer_class = OptimizerFactory._OPTIMIZERS[optimizer_type]
        optimizer_class_name = optimizer_class.__name__

        # Get metric class name for import
        metric_type = config.metric_type.lower()
        if metric_type in METRIC_CLASS_MAP:
            metric_class_name = METRIC_CLASS_MAP[metric_type][
                0
            ]  # Extract class name from tuple
        else:
            metric_class_name = "BaseMetric"

        # Generate optimizer instantiation code
        optimizer_code = OptimizerCodeGenerator.generate(optimizer_type, config)

        # Generate metric function code
        metric_code = MetricCodeGenerator.generate(metric_type, config)

        # Format prompt messages as Python list
        prompt_messages_code = format_python_value(config.prompt_messages)

        # Format runtime parameters as keyword arguments (not dict unpacking)
        runtime_params_kwargs = cls._format_runtime_params_as_kwargs(
            OPTIMIZER_RUNTIME_PARAMS
        )

        # Format dataset name
        dataset_name_code = format_python_value(config.dataset_name)

        # Format optimization ID for server-side code
        optimization_id_code = format_python_value(str(context.optimization_id))

        # Generate code based on target (server vs user download)
        if for_user_download:
            template = USER_CODE_TEMPLATE
        else:
            template = RUN_CODE_TEMPLATE

        # Fill in template placeholders
        code = template.format(
            optimizer_class_name=optimizer_class_name,
            metric_class_name=metric_class_name,
            dataset_name=dataset_name_code,
            prompt_messages=prompt_messages_code,
            metric_code=metric_code,
            optimizer_code=optimizer_code,
            runtime_params=runtime_params_kwargs,
            optimization_id=optimization_id_code,
        )

        return code

    @classmethod
    def _format_runtime_params_as_kwargs(cls, params: Dict[str, Any]) -> str:
        """Format runtime parameters as keyword arguments instead of dict unpacking.

        Args:
            params: Dictionary of runtime parameters

        Returns:
            String with keyword arguments, one per line with proper indentation
        """
        if not params:
            return ""

        kwargs = []
        for key, value in params.items():
            value_str = format_python_value(value)
            kwargs.append(f"    {key}={value_str}")

        return ",\n".join(kwargs)

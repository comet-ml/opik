import argparse
import inspect
import os
import re
from pathlib import Path
from typing import Any, get_type_hints

# Suppress banner for helper scripts
os.environ["OPIK_OPTIMIZER_NO_BANNER"] = "1"

import opik_optimizer  # noqa: E402
from pydantic import BaseModel


class ParameterInfo(BaseModel):
    path: str
    type: str | None
    default: Any | None
    required: bool
    description: str | None = None


class MethodInfo(BaseModel):
    name: str
    docstring: str | None
    parameters: list[ParameterInfo]


class ClassInfo(BaseModel):
    init_parameters: list[ParameterInfo]
    public_methods: list[MethodInfo]


class ClassInspector:
    def __init__(self, cls: type):
        self.cls = cls

    def get_docstring(self) -> str | None:
        return inspect.getdoc(self.cls)

    def parse_param_descriptions(self, docstring: str) -> dict[str, str]:
        """
        Parses the 'Args:' section of a docstring and extracts parameter descriptions.
        Handles multi-line descriptions by continuing until the next parameter or end of Args section.
        """
        param_desc: dict[str, str] = {}
        if not docstring:
            return param_desc

        lines = docstring.splitlines()
        in_args_section = False
        current_param = None
        current_description_lines = []

        for line in lines:
            stripped = line.strip()

            # Check if we're entering the Args section
            if stripped.startswith("Args:") or stripped.startswith("Arguments:"):
                in_args_section = True
                continue

            if in_args_section:
                # Check if this is a new parameter (starts with word followed by colon)
                match = re.match(r"^(\**\w+):\s*(.*)", stripped)
                if match:
                    # Save previous parameter if exists
                    if current_param:
                        param_desc[current_param] = " ".join(current_description_lines)

                    # Start new parameter
                    param_name, description = match.groups()
                    current_param = param_name
                    current_description_lines = [description] if description else []
                elif stripped == "":
                    # Empty line might indicate end of Args section
                    # But only if we haven't started a parameter yet, or if next non-empty line doesn't continue
                    continue
                elif current_param and stripped:
                    # Continuation of current parameter description
                    current_description_lines.append(stripped)
                elif not stripped and current_param:
                    # Empty line while processing a parameter - might be end of Args
                    continue
                else:
                    # Non-parameter line that doesn't match expected format - end of Args section
                    break

        # Don't forget to save the last parameter
        if current_param:
            param_desc[current_param] = " ".join(current_description_lines)

        return param_desc

    def get_pydantic_model_fields(self) -> list[ParameterInfo]:
        fields = []
        for name, model_field in self.cls.model_fields.items():  # type: ignore
            default = model_field.default if model_field.default is not None else None
            is_required = model_field.is_required()
            annotation = model_field.annotation

            try:
                type_str = str(annotation).replace("typing.", "")
            except Exception:
                type_str = "Any"

            description = model_field.description or None

            fields.append(
                ParameterInfo(
                    path=name,
                    type=type_str,
                    default=default,
                    required=is_required,
                    description=description,
                )
            )
        return fields

    def parse_signature(
        self,
        func: Any,
        docstring: str | None = None,
    ) -> list[ParameterInfo]:
        sig = inspect.signature(func)
        try:
            type_hints = get_type_hints(func, globalns=vars(inspect.getmodule(func)))
        except Exception:
            type_hints = {}
        param_docs = self.parse_param_descriptions(docstring or "")

        parameters = []
        for param in sig.parameters.values():
            if param.name == "self":
                continue

            param_type = type_hints.get(param.name, None)
            if param_type is None:
                param_type_str = "Any"
            else:
                param_type_str = (
                    param_type.__name__
                    if hasattr(param_type, "__name__")
                    else str(param_type)
                )
            is_required = param.default is inspect._empty
            default_value = None if is_required else param.default

            parameters.append(
                ParameterInfo(
                    path=param.name,
                    type=param_type_str,
                    default=default_value,
                    required=is_required,
                    description=param_docs.get(param.name),
                )
            )

        return parameters

    def get_public_methods(self) -> list[MethodInfo]:
        if issubclass(self.cls, BaseModel):
            return []

        methods = inspect.getmembers(self.cls, predicate=inspect.isfunction)
        public_methods_info = []

        for name, func in methods:
            if name.startswith("_") and name != "__init__":
                continue

            if name == "__init__":
                continue  # Handled separately

            # Skip methods that are not defined in this class if they raise NotImplementedError
            # (inherited from base and not overridden)
            if not self._is_method_defined_in_class(name):
                if self._method_raises_not_implemented(func):
                    continue

            # Skip methods that are defined in this class but immediately raise NotImplementedError
            if self._is_method_defined_in_class(name):
                if self._method_raises_not_implemented(func):
                    continue

            doc = inspect.getdoc(func) or ""
            parameters = self.parse_signature(func, doc)

            public_methods_info.append(
                MethodInfo(name=name, docstring=doc, parameters=parameters)
            )

        return public_methods_info

    def _is_method_defined_in_class(self, method_name: str) -> bool:
        """Check if a method is defined in the class itself, not just inherited."""
        return method_name in self.cls.__dict__

    def _method_raises_not_implemented(self, func: Any) -> bool:
        """Check if a method only raises NotImplementedError without doing anything else."""
        try:
            source = inspect.getsource(func)
            lines = source.split("\n")

            # Find where the function body starts (after the def line and parameters)
            body_start = 0
            in_signature = True
            for i, line in enumerate(lines):
                if in_signature:
                    # Look for the closing of the function signature
                    if ")" in line and ":" in line:
                        body_start = i + 1
                        in_signature = False
                        break

            # Parse the body to find actual code (skip docstrings)
            body_lines = lines[body_start:]
            in_docstring = False
            docstring_char = None
            actual_code_lines = []

            for line in body_lines:
                stripped = line.strip()

                # Handle docstring start/end
                if not in_docstring:
                    if stripped.startswith('"""') or stripped.startswith("'''"):
                        docstring_char = stripped[:3]
                        in_docstring = True
                        # Check if docstring ends on same line
                        if stripped.endswith(docstring_char):
                            in_docstring = False
                        continue
                else:
                    # We're in a docstring, check if it ends
                    if docstring_char is not None and docstring_char in stripped:
                        in_docstring = False
                    continue

                # Skip empty lines and comments
                if not stripped or stripped.startswith("#"):
                    continue

                # This is actual code
                actual_code_lines.append(stripped)

                # Stop after finding a few lines of actual code
                if len(actual_code_lines) >= 5:
                    break

            # If the only actual code is raising NotImplementedError, skip this method
            if actual_code_lines:
                # Check if first line is raise NotImplementedError
                if actual_code_lines[0].startswith("raise NotImplementedError"):
                    return True

            return False
        except (OSError, TypeError):
            # Can't get source, assume it's implemented
            return False

    def inspect(self) -> ClassInfo:
        if issubclass(self.cls, BaseModel):
            init_params = self.get_pydantic_model_fields()
        else:
            init_func = getattr(self.cls, "__init__", None)
            # Use class docstring for parameter descriptions, not __init__ docstring
            # This is because Python conventionally puts parameter docs in the class docstring
            class_doc = self.get_docstring()
            init_params = (
                self.parse_signature(init_func, class_doc) if init_func else []
            )

        return ClassInfo(
            init_parameters=init_params,
            public_methods=self.get_public_methods(),
        )

    def format_param_field(self, param: ParameterInfo) -> str:
        def _escape_mdx_text(text: str) -> str:
            return text.replace("{", "&#123;").replace("}", "&#125;")

        field = f'<ParamField path="{param.path}" type="{param.type or "Any"}"'
        if not param.required:
            field += " optional={true}"
        if param.default is not None and param.default != inspect._empty:
            field += f' default="{param.default}"'

        if param.description:
            field += f">{_escape_mdx_text(param.description)}</ParamField>"
        else:
            field += " />"

        return field

    def format_method_signature(
        self, name: str, parameters: list[ParameterInfo]
    ) -> str:
        if len(parameters) == 0:
            lines = [f"{name}()"]
        else:
            lines = [f"{name}("]
            param_lines = []

            for param in parameters:
                type_str = param.type or "Any"
                if not param.required:
                    default_repr = f" = {repr(param.default)}"
                else:
                    default_repr = ""
                param_lines.append(f"    {param.path}: {type_str}{default_repr}")

            if param_lines:
                lines.append(",\n".join(param_lines))
            lines.append(")")

        lines = ["```python"] + lines + ["```\n"]
        return "\n".join(lines)

    def to_render_string(self) -> str:
        info = self.inspect()
        output = []

        # Class header and constructor
        output.append(f"## {self.cls.__name__}\n")
        output.append(
            self.format_method_signature(self.cls.__name__, info.init_parameters)
        )
        output.append("")

        if info.init_parameters and len(info.init_parameters) > 0:
            output.append("**Parameters:**")
            output.append("")
            for param in info.init_parameters:
                output.append(self.format_param_field(param))
            output.append("")

        # Methods
        if len(info.public_methods) > 0:
            output.append("### Methods")
        for method in info.public_methods:
            output.append(f"#### {method.name}")
            output.append(self.format_method_signature(method.name, method.parameters))
            output.append("")

            if method.parameters and len(method.parameters) > 0:
                output.append("**Parameters:**")
                output.append("")
                for param in method.parameters:
                    output.append(self.format_param_field(param))
                output.append("")

        return "\n".join(output)


def collect_public_classes() -> list[type]:
    public_names = getattr(opik_optimizer, "__all__", [])
    classes: list[type] = []
    seen: set[int] = set()

    for name in public_names:
        obj = getattr(opik_optimizer, name, None)
        if inspect.isclass(obj) and id(obj) not in seen:
            classes.append(obj)
            seen.add(id(obj))

    # PromptLibrary is a public customization surface but not re-exported at top-level.
    from opik_optimizer.utils.prompt_library import PromptLibrary

    if id(PromptLibrary) not in seen:
        classes.append(PromptLibrary)

    return classes


classes_to_document = collect_public_classes()


res = """---
title: "Opik Agent Optimizer API Reference"
subtitle: "Technical SDK reference guide"
---

The Opik Agent Optimizer SDK provides a comprehensive set of tools for optimizing LLM prompts and agents. This reference guide documents the standardized API that all optimizers follow, ensuring consistency and interoperability across different optimization algorithms.

## Key Features

- **Standardized API**: All optimizers follow the same interface for `optimize_prompt()` methods
- **Multiple Algorithms**: Support for various optimization strategies including evolutionary, few-shot, meta-prompt, and GEPA
- **MCP Support**: Built-in support for Model Context Protocol tool calling and optimization
- **Consistent Results**: All optimizers return standardized `OptimizationResult` objects
- **Counter Tracking**: Built-in LLM and tool call counters for monitoring usage
- **Backward Compatibility**: All original parameters preserved through kwargs extraction
- **Deprecation Warnings**: Clear warnings for deprecated parameters with migration guidance

## Core Classes

The SDK provides several optimizer classes that all inherit from `BaseOptimizer` and implement the same standardized interface:

- **ParameterOptimizer**: Optimizes LLM call parameters (temperature, top_p, etc.) using Bayesian optimization
- **FewShotBayesianOptimizer**: Uses few-shot learning with Bayesian optimization
- **MetaPromptOptimizer**: Employs meta-prompting techniques for optimization
- **EvolutionaryOptimizer**: Uses genetic algorithms for prompt evolution
- **GepaOptimizer**: Leverages GEPA (Genetic-Pareto) optimization approach
- **HRPO (Hierarchical Reflective Prompt Optimizer)**: Uses hierarchical root cause analysis for targeted prompt refinement

## Standardized Method Signatures

All optimizers implement these core methods with identical signatures:

### optimize_prompt()
```python
def optimize_prompt(
    self,
    prompt: ChatPrompt | dict[str, ChatPrompt],
    dataset: Dataset,
    metric: MetricFunction,
    agent: OptimizableAgent | None = None,
    experiment_config: dict | None = None,
    n_samples: int | None = None,
    auto_continue: bool = False,
    project_name: str | None = None,
    optimization_id: str | None = None,
    validation_dataset: Dataset | None = None,
    max_trials: int = 10,
    allow_tool_use: bool = True,
    **kwargs: Any,
) -> OptimizationResult
```

## Deprecation Warnings

The following parameters are deprecated and will be removed in future versions:

### Constructor Parameters

- **`num_threads`** in optimizer constructors: Use `n_threads` instead

### Example Migration

```python
# ❌ Deprecated
optimizer = FewShotBayesianOptimizer(
    model="gpt-4o-mini",
    num_threads=16,  # Deprecated
)

# ✅ Correct
optimizer = FewShotBayesianOptimizer(
    model="gpt-4o-mini",
    n_threads=16,  # Use n_threads instead
)
```

"""

for class_obj in classes_to_document:
    inspector = ClassInspector(class_obj)
    res += f"{inspector.to_render_string()}\n"


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate Fern documentation for Opik Optimizer API"
    )
    parser.add_argument(
        "--write",
        action="store_true",
        help="Write output to Fern docs file (default: print to stdout)",
    )
    args = parser.parse_args()

    if args.write:
        # Path to Fern docs
        script_dir = Path(__file__).parent
        repo_root = script_dir.parent.parent.parent  # Go up to repo root
        fern_docs_path = (
            repo_root
            / "apps"
            / "opik-documentation"
            / "documentation"
            / "fern"
            / "docs"
            / "agent_optimization"
            / "opik_optimizer"
            / "reference.mdx"
        )

        # Verify the file exists before overwriting
        if not fern_docs_path.exists():
            print(f"⚠️  Warning: Target file does not exist: {fern_docs_path}")
            print("Creating new file...")
            fern_docs_path.parent.mkdir(parents=True, exist_ok=True)

        # Write the file
        fern_docs_path.write_text(res, encoding="utf-8")
        print(f"✅ Documentation written to: {fern_docs_path}")
    else:
        print(res)


if __name__ == "__main__":
    main()

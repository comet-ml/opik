import inspect
import re
from typing import get_origin, get_args
from typing import Any, Dict, List, Optional, get_type_hints

from pydantic import BaseModel
from pydantic.fields import FieldInfo


class ParameterInfo(BaseModel):
    path: str
    type: Optional[str]
    default: Optional[Any]
    required: bool
    description: Optional[str] = None


class MethodInfo(BaseModel):
    name: str
    docstring: Optional[str]
    parameters: List[ParameterInfo]


class ClassInfo(BaseModel):
    class_docstring: Optional[str]
    init_parameters: List[ParameterInfo]
    public_methods: List[MethodInfo]


class ClassInspector:
    def __init__(self, cls: type):
        self.cls = cls

    def get_docstring(self) -> Optional[str]:
        return inspect.getdoc(self.cls)

    def parse_param_descriptions(self, docstring: str) -> Dict[str, str]:
        """
        Parses the 'Args:' section of a docstring and extracts parameter descriptions.
        """
        param_desc = {}
        if not docstring:
            return param_desc

        lines = docstring.splitlines()
        in_args_section = False

        for line in lines:
            stripped = line.strip()
            if stripped.startswith("Args:") or stripped.startswith("Arguments:"):
                in_args_section = True
                continue
            if in_args_section:
                if stripped == "" or not re.match(r"^\**\w+:", stripped):
                    break
                match = re.match(r"^(\**\w+):\s*(.*)", stripped)
                if match:
                    param_name, description = match.groups()
                    param_desc[param_name] = description

        return param_desc

    def get_pydantic_model_fields(self) -> List[ParameterInfo]:
        fields = []
        for name, model_field in self.cls.model_fields.items():
            field_info: FieldInfo = model_field.annotation
            default = model_field.default if model_field.default is not None else None
            is_required = model_field.is_required()
            annotation = model_field.annotation

            try:
                type_str = str(annotation).replace("typing.", "")
            except Exception:
                type_str = "Any"

            description = model_field.description or None

            fields.append(ParameterInfo(
                path=name,
                type=type_str,
                default=default,
                required=is_required,
                description=description
            ))
        return fields

    def parse_signature(
        self,
        func: Any,
        docstring: Optional[str] = None,
    ) -> List[ParameterInfo]:
        sig = inspect.signature(func)
        try:
            type_hints = get_type_hints(func, globalns=vars(inspect.getmodule(func)))
        except Exception:
            type_hints = {}
        param_docs = self.parse_param_descriptions(docstring or "")

        parameters = []
        for param in sig.parameters.values():
            if param.name == 'self':
                continue

            param_type = type_hints.get(param.name, None)
            param_type_str = param_type.__name__ if hasattr(param_type, '__name__') else str(param_type)
            is_required = param.default is inspect._empty
            default_value = None if is_required else param.default

            parameters.append(ParameterInfo(
                path=param.name,
                type=param_type_str,
                default=default_value,
                required=is_required,
                description=param_docs.get(param.name)
            ))

        return parameters

    def get_public_methods(self) -> List[MethodInfo]:
        if issubclass(self.cls, BaseModel):
            return []

        methods = inspect.getmembers(self.cls, predicate=inspect.isfunction)
        public_methods_info = []

        for name, func in methods:
            if name.startswith('_') and name != '__init__':
                continue

            if name == '__init__':
                continue  # Handled separately

            doc = inspect.getdoc(func) or ""
            parameters = self.parse_signature(func, doc)

            public_methods_info.append(MethodInfo(
                name=name,
                docstring=doc,
                parameters=parameters
            ))

        return public_methods_info

    def inspect(self) -> ClassInfo:
        if issubclass(self.cls, BaseModel):
            init_params = self.get_pydantic_model_fields()
        else:
            init_func = getattr(self.cls, '__init__', None)
            init_doc = inspect.getdoc(init_func) if init_func else None
            init_params = self.parse_signature(init_func, init_doc) if init_func else []

        return ClassInfo(
            class_docstring=self.get_docstring(),
            init_parameters=init_params,
            public_methods=self.get_public_methods()
        )

    def format_param_field(self, param: ParameterInfo) -> str:
        field = f'<ParamField path="{param.path}" type="{param.type or "Any"}"'
        if not param.required:
            field += ' optional={true}'
        if param.default is not None and param.default != inspect._empty:
            field += f' defaultValue="{param.default}"'
        
        if param.description:
            field += f'>{param.description}</ParamField>'
        else:
            field += ' />'

        return field

    def format_method_signature(self, name: str, parameters: List[ParameterInfo]) -> str:
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
        output.append(self.format_method_signature(self.cls.__name__, info.init_parameters))
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

import opik_optimizer

classes_to_document = [
    opik_optimizer.FewShotBayesianOptimizer,
    opik_optimizer.MetaPromptOptimizer,
    opik_optimizer.EvolutionaryOptimizer,
    opik_optimizer.ChatPrompt,
    opik_optimizer.OptimizationResult,
    # opik_optimizer.datasets
    ]


res = """---
title: "Opik Agent Optimizer API Reference"
subtitle: "Technical SDK reference guide"
pytest_codeblocks_skip: true
---

The Opik Agent Optimizer SDK provides a set of tools for optimizing LLM prompts. This reference
guide will help you understand the available APIs and how to use them effectively.

"""

for class_obj in classes_to_document:
    inspector = ClassInspector(class_obj)
    res += f"{inspector.to_render_string()}\n"

print(res)

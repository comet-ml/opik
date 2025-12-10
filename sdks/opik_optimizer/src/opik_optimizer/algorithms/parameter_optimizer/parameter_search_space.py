"""Parameter search space for collections of tunable parameters."""

from __future__ import annotations

import copy
from typing import Any
from collections.abc import Mapping, Sequence

from optuna.trial import Trial
from pydantic import BaseModel, Field, model_validator

from .parameter_spec import ParameterSpec


class ParameterSearchSpace(BaseModel):
    """Collection of parameters to explore during optimization."""

    parameters: list[ParameterSpec] = Field(default_factory=list)

    model_config = {
        "extra": "forbid",
    }

    @model_validator(mode="before")
    @classmethod
    def _normalize(cls, data: Any) -> Any:
        if isinstance(data, ParameterSearchSpace):
            return data
        if isinstance(data, Mapping):
            if "parameters" in data:
                return data
            parameters = []
            for name, spec in data.items():
                if isinstance(spec, Mapping):
                    spec_dict = dict(spec)
                elif isinstance(spec, ParameterSpec):
                    spec_dict = spec.model_dump()
                else:
                    raise TypeError(
                        "Parameter definitions must be mappings or ParameterSpec instances"
                    )
                spec_dict.setdefault("name", name)
                parameters.append(spec_dict)
            return {"parameters": parameters}
        if isinstance(data, Sequence):
            return {"parameters": list(data)}
        return data

    @model_validator(mode="after")
    def _validate(self) -> ParameterSearchSpace:
        names = [spec.name for spec in self.parameters]
        if len(names) != len(set(names)):
            duplicates = {name for name in names if names.count(name) > 1}
            raise ValueError(
                f"Duplicate parameter names detected: {', '.join(sorted(duplicates))}"
            )
        if not self.parameters:
            raise ValueError("Parameter search space cannot be empty")
        return self

    def suggest(self, trial: Trial) -> dict[str, Any]:
        """Sample a set of parameter values using an Optuna trial."""
        return {spec.name: spec.suggest(trial) for spec in self.parameters}

    def apply(
        self,
        prompt: Any,  # ChatPrompt type
        values: Mapping[str, Any],
        *,
        base_model_kwargs: dict[str, Any] | None = None,
    ) -> Any:  # Returns ChatPrompt
        """Return a prompt copy with sampled values applied."""
        prompt_copy = prompt.copy()
        if base_model_kwargs is not None:
            prompt_copy.model_kwargs = copy.deepcopy(base_model_kwargs)
        for spec in self.parameters:
            if spec.name in values:
                spec.apply_to_prompt(prompt_copy, values[spec.name])
        return prompt_copy

    def values_to_model_kwargs(
        self,
        values: Mapping[str, Any],
        *,
        base: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """Produce a model_kwargs dictionary with sampled values applied."""
        model_kwargs = copy.deepcopy(base) if base is not None else {}
        for spec in self.parameters:
            if spec.name in values:
                spec.apply_to_model_kwargs(model_kwargs, values[spec.name])
        return model_kwargs

    def model_dump(self, *args: Any, **kwargs: Any) -> dict[str, Any]:
        """Ensure dumping keeps parameter definitions accessible."""
        return super().model_dump(*args, **kwargs)

    def narrow_around(
        self, values: Mapping[str, Any], scale: float
    ) -> ParameterSearchSpace:
        """Return a new search space narrowed around provided parameter values."""

        narrowed: list[ParameterSpec] = []
        for spec in self.parameters:
            value = values.get(spec.name)
            narrowed.append(spec.narrow(value, scale))
        return ParameterSearchSpace(parameters=narrowed)

    def describe(self) -> dict[str, dict[str, Any]]:
        """Return a human-friendly description of each parameter range."""

        summary: dict[str, dict[str, Any]] = {}
        for spec in self.parameters:
            entry: dict[str, Any] = {"type": spec.distribution.value}
            if spec.distribution.value in {"float", "int"}:
                entry["min"] = spec.low
                entry["max"] = spec.high
                if spec.step is not None:
                    entry["step"] = spec.step
                entry["scale"] = spec.scale
            else:
                if spec.choices is not None:
                    entry["choices"] = list(spec.choices)
            summary[spec.name] = entry
        return summary

    def expand_for_prompts(self, prompt_names: list[str]) -> ParameterSearchSpace:
        """
        Expand parameter space for multiple prompts.

        For each parameter without a prompt prefix, creates prompt-specific
        versions. Parameters already prefixed (e.g., 'analyze.temperature')
        are kept as-is.

        Args:
            prompt_names: List of prompt names to expand parameters for.

        Returns:
            New ParameterSearchSpace with expanded parameters.

        Example:
            Input: {"temperature": {...}} with prompts ["analyze", "respond"]
            Output: {"analyze.temperature": {...}, "respond.temperature": {...}}

            Input: {"analyze.temperature": {...}} (already prefixed)
            Output: {"analyze.temperature": {...}} (unchanged)
        """
        expanded_params: list[ParameterSpec] = []

        for spec in self.parameters:
            # Check if parameter already has a prompt prefix
            has_prefix = any(spec.name.startswith(f"{name}.") for name in prompt_names)

            if has_prefix:
                # Keep as-is
                expanded_params.append(spec)
            else:
                # Expand for each prompt
                for prompt_name in prompt_names:
                    new_spec = spec.model_copy(deep=True)
                    new_spec.name = f"{prompt_name}.{spec.name}"
                    expanded_params.append(new_spec)

        return ParameterSearchSpace(parameters=expanded_params)

    def apply_to_prompts(
        self,
        prompts: Mapping[str, Any],  # dict[str, ChatPrompt]
        values: Mapping[str, Any],
        *,
        base_model_kwargs: dict[str, Any] | None = None,
    ) -> dict[str, Any]:  # Returns dict[str, ChatPrompt]
        """
        Apply prompt-specific parameter values to each prompt.

        Expects values with prompt prefixes like:
        {"analyze.temperature": 0.7, "respond.temperature": 0.5}

        The method matches spec names (e.g., "analyze.temperature") to values,
        then applies the underlying parameter (e.g., "temperature") to each prompt.

        Args:
            prompts: Dictionary mapping prompt names to ChatPrompt objects.
            values: Dictionary of parameter values with prompt prefixes.
            base_model_kwargs: Optional base model kwargs to start from.

        Returns:
            Dictionary mapping prompt names to tuned ChatPrompt copies.
        """
        result: dict[str, Any] = {}

        for prompt_name, prompt in prompts.items():
            prompt_copy = prompt.copy()
            if base_model_kwargs is not None:
                prompt_copy.model_kwargs = copy.deepcopy(base_model_kwargs)

            # Find and apply all specs that match this prompt's prefix
            for spec in self.parameters:
                if spec.name in values and spec.name.startswith(f"{prompt_name}."):
                    # Get the base parameter name (without prompt prefix)
                    base_param_name = spec.name.split(".", 1)[1]

                    # Create a temporary spec with the base name for applying
                    temp_spec = spec.model_copy(deep=True)
                    object.__setattr__(temp_spec, "name", base_param_name)
                    # Re-resolve target with base name
                    object.__setattr__(
                        temp_spec, "_resolved_target", temp_spec._resolve_target()
                    )

                    temp_spec.apply_to_prompt(prompt_copy, values[spec.name])

            result[prompt_name] = prompt_copy

        return result

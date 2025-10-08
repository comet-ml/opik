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

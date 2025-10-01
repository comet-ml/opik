"""Search space definitions for parameter optimization."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any, Literal, Mapping, Sequence

import math

import copy

from optuna.trial import Trial
from pydantic import BaseModel, Field, PrivateAttr, model_validator

from typing import TYPE_CHECKING

if TYPE_CHECKING:  # pragma: no cover
    from ..optimization_config.chat_prompt import ChatPrompt


class ParameterType(str, Enum):
    """Supported parameter distribution types."""

    FLOAT = "float"
    INT = "int"
    CATEGORICAL = "categorical"
    BOOL = "bool"


@dataclass(frozen=True)
class ResolvedTarget:
    root: Literal["model", "model_kwargs"]
    path: tuple[str, ...]


class ParameterSpec(BaseModel):
    """Definition for a single tunable parameter."""

    name: str
    description: str | None = None
    distribution: ParameterType = Field(alias="type")
    low: float | None = Field(default=None, alias="min")
    high: float | None = Field(default=None, alias="max")
    step: float | None = None
    scale: Literal["linear", "log"] = "linear"
    choices: list[Any] | None = None
    target: str | Sequence[str] | None = None
    default: Any | None = None

    model_config = {
        "populate_by_name": True,
        "extra": "forbid",
    }

    _resolved_target: ResolvedTarget | None = PrivateAttr(default=None)

    @model_validator(mode="before")
    @classmethod
    def _coerce_aliases(cls, data: Any) -> Any:
        if isinstance(data, Mapping):
            data = dict(data)
            if "values" in data and "choices" not in data:
                data["choices"] = data.pop("values")
            if "selection" in data and "choices" not in data:
                data["choices"] = data.pop("selection")
            if "path" in data and "target" not in data:
                data["target"] = data.pop("path")
        return data

    @model_validator(mode="after")
    def _validate(self) -> "ParameterSpec":
        if self.distribution in {ParameterType.FLOAT, ParameterType.INT}:
            if self.low is None or self.high is None:
                raise ValueError("'min' and 'max' must be provided for range parameters")
            if self.low >= self.high:
                raise ValueError("'min' must be less than 'max'")
            if self.scale not in {"linear", "log"}:
                raise ValueError("scale must be 'linear' or 'log'")
            if self.scale == "log" and (self.low <= 0 or self.high <= 0):
                raise ValueError("log-scaled parameters require positive bounds")
            if self.step is not None and self.step <= 0:
                raise ValueError("step must be positive when provided")

            if self.distribution == ParameterType.INT:
                self.low = int(self.low)
                self.high = int(self.high)
                if self.step is not None:
                    self.step = int(self.step)
        elif self.distribution == ParameterType.CATEGORICAL:
            if not self.choices:
                raise ValueError("categorical parameters require non-empty 'choices'")
        elif self.distribution == ParameterType.BOOL:
            if not self.choices:
                self.choices = [False, True]
        else:  # pragma: no cover - safety fallback
            raise ValueError(f"Unsupported distribution: {self.distribution}")

        self._resolved_target = self._resolve_target()
        return self

    @property
    def target_path(self) -> ResolvedTarget:
        if self._resolved_target is None:
            self._resolved_target = self._resolve_target()
        return self._resolved_target

    def suggest(self, trial: Trial) -> Any:
        """Return a sampled value for this parameter from Optuna."""
        if self.distribution == ParameterType.FLOAT:
            return trial.suggest_float(
                self.name,
                float(self.low),
                float(self.high),
                step=self.step,
                log=self.scale == "log",
            )
        if self.distribution == ParameterType.INT:
            return trial.suggest_int(
                self.name,
                int(self.low),
                int(self.high),
                step=int(self.step) if self.step is not None else 1,
                log=self.scale == "log",
            )
        if self.distribution in {ParameterType.CATEGORICAL, ParameterType.BOOL}:
            assert self.choices is not None  # guarded in validators
            return trial.suggest_categorical(self.name, list(self.choices))
        raise RuntimeError(f"Unsupported distribution type: {self.distribution}")

    def apply_to_prompt(self, prompt: "ChatPrompt", value: Any) -> None:
        """Apply a sampled value to the provided prompt instance."""
        resolved = self.target_path
        if resolved.root == "model":
            if resolved.path:
                raise ValueError("Nested paths under 'model' are not supported")
            prompt.model = value
            return

        if prompt.model_kwargs is None:
            prompt.model_kwargs = {}

        self._assign_nested(prompt.model_kwargs, resolved.path, value)

    def apply_to_model_kwargs(self, model_kwargs: dict[str, Any], value: Any) -> None:
        """Apply a sampled value to a model_kwargs dictionary."""
        resolved = self.target_path
        if resolved.root != "model_kwargs":
            return
        self._assign_nested(model_kwargs, resolved.path, value)

    def narrow(self, center: Any, scale: float) -> "ParameterSpec":
        """Return a narrowed version of the spec around the provided center."""

        if center is None or scale <= 0:
            return self

        if self.distribution in {ParameterType.FLOAT, ParameterType.INT}:
            if self.low is None or self.high is None:
                return self

            span = float(self.high) - float(self.low)
            if span <= 0:
                return self

            half_window = span * float(scale) / 2
            if half_window <= 0:
                return self

            center_val = float(center)
            new_low = max(float(self.low), center_val - half_window)
            new_high = min(float(self.high), center_val + half_window)

            if self.distribution == ParameterType.INT:
                new_low = math.floor(new_low)
                new_high = math.ceil(new_high)
                if new_low == new_high:
                    new_high = min(int(self.high), new_low + 1)
                if new_low == new_high:
                    return self

            if new_low >= new_high:
                return self

            spec_dict = self.model_dump(by_alias=True)
            spec_dict["min"] = new_low
            spec_dict["max"] = new_high
            return ParameterSpec.model_validate(spec_dict)

        # Non-numeric parameters remain unchanged
        return self

    def _assign_nested(
        self, container: dict[str, Any], path: Sequence[str], value: Any
    ) -> None:
        if not path:
            container[self.name] = value
            return
        current = container
        for key in path[:-1]:
            next_val = current.get(key)
            if not isinstance(next_val, dict):
                next_val = {}
            current[key] = next_val
            current = next_val
        current[path[-1]] = value

    def _resolve_target(self) -> ResolvedTarget:
        target = self.target
        if target is None:
            return ResolvedTarget("model_kwargs", (self.name,))

        if isinstance(target, str):
            tokens = tuple(filter(None, (part.strip() for part in target.split("."))))
        else:
            tokens = tuple(target)

        if not tokens:
            return ResolvedTarget("model_kwargs", (self.name,))

        root = tokens[0]
        path = tokens[1:]

        if root not in {"model", "model_kwargs"}:
            root = "model_kwargs"
            path = tokens

        return ResolvedTarget(root, tuple(path))


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
    def _validate(self) -> "ParameterSearchSpace":
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
        prompt: "ChatPrompt",
        values: Mapping[str, Any],
        *,
        base_model_kwargs: dict[str, Any] | None = None,
    ) -> "ChatPrompt":
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
    ) -> "ParameterSearchSpace":
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
            if spec.distribution in {ParameterType.FLOAT, ParameterType.INT}:
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

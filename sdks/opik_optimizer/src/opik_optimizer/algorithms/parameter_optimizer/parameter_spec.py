"""Parameter specification for defining tunable parameters."""

from __future__ import annotations

import math
from typing import Any, Literal
from collections.abc import Mapping, Sequence

from optuna.trial import Trial
from pydantic import BaseModel, Field, PrivateAttr, model_validator

from .search_space_types import ParameterType, ResolvedTarget


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
    def _validate(self) -> ParameterSpec:
        if self.distribution in {ParameterType.FLOAT, ParameterType.INT}:
            if self.low is None or self.high is None:
                raise ValueError(
                    "'min' and 'max' must be provided for range parameters"
                )
            if self.low >= self.high:
                raise ValueError("'min' must be less than 'max'")
            if self.scale not in {"linear", "log"}:
                raise ValueError("scale must be 'linear' or 'log'")
            if self.scale == "log" and (self.low <= 0 or self.high <= 0):
                raise ValueError("log-scaled parameters require positive bounds")
            if self.step is not None and self.step <= 0:
                raise ValueError("step must be positive when provided")

            if self.distribution == ParameterType.INT:
                object.__setattr__(self, "low", int(self.low))
                object.__setattr__(self, "high", int(self.high))
                if self.step is not None:
                    object.__setattr__(self, "step", int(self.step))
        elif self.distribution == ParameterType.CATEGORICAL:
            if not self.choices:
                raise ValueError("categorical parameters require non-empty 'choices'")
        elif self.distribution == ParameterType.BOOL:
            if not self.choices:
                object.__setattr__(self, "choices", [False, True])
        else:  # pragma: no cover - safety fallback
            raise ValueError(f"Unsupported distribution: {self.distribution}")

        object.__setattr__(self, "_resolved_target", self._resolve_target())
        return self

    @property
    def target_path(self) -> ResolvedTarget:
        if self._resolved_target is None:
            self._resolved_target = self._resolve_target()
        return self._resolved_target

    def suggest(self, trial: Trial) -> Any:
        """Return a sampled value for this parameter from Optuna."""
        if self.distribution == ParameterType.FLOAT:
            assert self.low is not None and self.high is not None  # validated earlier
            return trial.suggest_float(
                self.name,
                float(self.low),
                float(self.high),
                step=self.step,
                log=self.scale == "log",
            )
        if self.distribution == ParameterType.INT:
            assert self.low is not None and self.high is not None  # validated earlier
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

    def apply_to_prompt(
        self,
        prompt: Any,
        value: Any,  # ChatPrompt type
    ) -> None:
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

    def narrow(self, center: Any, scale: float) -> ParameterSpec:
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

        return ResolvedTarget(root, tuple(path))  # type: ignore[arg-type]

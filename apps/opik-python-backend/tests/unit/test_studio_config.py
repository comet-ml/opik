"""Unit tests for opik_backend.studio.config sizing policy."""

import importlib

import pytest

from opik_backend.studio import config as config_module
from opik_backend.studio.config import (
    DATASET_SAMPLES,
    GEPA_MIN_REFLECTION_ITERATIONS,
    GEPA_REFLECTION_MINIBATCH_ENV,
    GEPA_REFLECTION_MINIBATCH_MAX,
    resolve_reflection_minibatch_size,
)


class TestFloatEnvValidation:
    """float() alone accepts nan/inf, which silently breaks the score comparisons
    these values feed — a bad deployment value must fail at startup instead."""

    @pytest.mark.parametrize(
        "env,default,minimum,maximum",
        [
            ("OPTIMIZER_PERFECT_SCORE", "1.0", 0.0, 1.0),
            ("OPTIMIZER_TASK_TEMPERATURE", "0.0", 0.0, 2.0),
        ],
    )
    @pytest.mark.parametrize("bad_value", ["nan", "inf", "-inf", "abc", "-0.5", "9.5"])
    def test_invalid_value__raises_naming_the_variable(
        self, env, default, minimum, maximum, bad_value, monkeypatch
    ):
        monkeypatch.setenv(env, bad_value)
        with pytest.raises(ValueError, match=env):
            config_module._read_float_env(
                env, default, minimum=minimum, maximum=maximum
            )

    def test_blank_value__uses_default(self, monkeypatch):
        monkeypatch.setenv("OPTIMIZER_PERFECT_SCORE", "   ")
        assert (
            config_module._read_float_env(
                "OPTIMIZER_PERFECT_SCORE", "1.0", minimum=0.0, maximum=1.0
            )
            == 1.0
        )

    def test_bounds_are_inclusive(self, monkeypatch):
        monkeypatch.setenv("OPTIMIZER_PERFECT_SCORE", "0.0")
        assert (
            config_module._read_float_env(
                "OPTIMIZER_PERFECT_SCORE", "1.0", minimum=0.0, maximum=1.0
            )
            == 0.0
        )

    def test_error_message_is_bounded(self, monkeypatch):
        monkeypatch.setenv("OPTIMIZER_PERFECT_SCORE", "x" * 5000)
        with pytest.raises(ValueError) as exc_info:
            config_module._read_float_env(
                "OPTIMIZER_PERFECT_SCORE", "1.0", minimum=0.0, maximum=1.0
            )
        assert len(str(exc_info.value)) < 200

    def test_module_import_fails_fast_on_malformed_env(self, monkeypatch):
        monkeypatch.setenv("OPTIMIZER_PERFECT_SCORE", "nan")
        with pytest.raises(ValueError, match="OPTIMIZER_PERFECT_SCORE"):
            importlib.reload(config_module)
        monkeypatch.delenv("OPTIMIZER_PERFECT_SCORE")
        importlib.reload(config_module)


class TestResolveReflectionMinibatchSize:
    """OPIK-7511: the reflection mini-batch scales with dataset size so coarse
    0/1 metrics get a usable gradient, capped by the dataset itself, by an
    absolute ceiling (the batch is serialized into the reflection prompt) and
    by the metric-call budget (>= GEPA_MIN_REFLECTION_ITERATIONS iterations)."""

    @pytest.mark.parametrize(
        "dataset_size,max_trials,expected",
        [
            # Single-item dataset: the batch is that one item.
            (1, 10, 1),
            # Tiny dataset below the floor of 5: capped at the dataset itself.
            (3, 10, 3),
            # Small dataset: the floor of 5 (previous fixed value) holds.
            (10, 10, 5),
            (25, 10, 5),
            # 20% scaling kicks in above the floor.
            (30, 10, 6),
            (40, 10, 8),
            (50, 10, 10),
            # No max_trials cap: 20% keeps scaling past max_trials=10
            # (previously clamped to 10 — the OPIK-7511 regression).
            (100, 10, 20),
            # ...up to the absolute ceiling, which bounds the reflection prompt
            # (the whole mini-batch is serialized into it).
            (200, 10, 25),
            (1000, 10, 25),
            # A small trial budget no longer strangles the batch...
            (100, 3, 20),
            # ...but the metric-call budget does: 100*1 // (2*5) = 10, which
            # keeps >= GEPA_MIN_REFLECTION_ITERATIONS reflection iterations.
            (100, 1, 10),
            # Degenerate budget: the cap floors at 1.
            (6, 1, 1),
        ],
    )
    def test_policy(self, dataset_size, max_trials, expected, monkeypatch):
        monkeypatch.delenv(GEPA_REFLECTION_MINIBATCH_ENV, raising=False)
        assert (
            resolve_reflection_minibatch_size(
                dataset_size=dataset_size, max_trials=max_trials
            )
            == expected
        )

    def test_budget_cap_guarantees_min_reflection_iterations(self, monkeypatch):
        """Whenever the resolved batch is > 1, the run's metric budget
        (max_trials * dataset_size) must fit at least
        GEPA_MIN_REFLECTION_ITERATIONS iterations at ~2*batch calls each."""
        monkeypatch.delenv(GEPA_REFLECTION_MINIBATCH_ENV, raising=False)
        for dataset_size in (1, 5, 30, 100, 1000):
            for max_trials in (1, 3, 10, 25):
                batch = resolve_reflection_minibatch_size(
                    dataset_size=dataset_size, max_trials=max_trials
                )
                if batch > 1:
                    budget = max_trials * dataset_size
                    assert budget // (2 * batch) >= GEPA_MIN_REFLECTION_ITERATIONS

    def test_batch_never_exceeds_the_prompt_ceiling(self, monkeypatch):
        """gepa serializes every mini-batch sample into one reflection prompt, so
        an unbounded batch is an unbounded prompt — no dataset size may push it
        past the ceiling, including the largest one the Studio can sample."""
        monkeypatch.delenv(GEPA_REFLECTION_MINIBATCH_ENV, raising=False)
        for dataset_size in (100, 250, 500, DATASET_SAMPLES):
            for max_trials in (1, 3, 10, 25, 100):
                assert (
                    resolve_reflection_minibatch_size(
                        dataset_size=dataset_size, max_trials=max_trials
                    )
                    <= GEPA_REFLECTION_MINIBATCH_MAX
                )

    def test_env_override_wins_verbatim(self, monkeypatch):
        monkeypatch.setenv(GEPA_REFLECTION_MINIBATCH_ENV, "7")
        assert resolve_reflection_minibatch_size(dataset_size=1000, max_trials=10) == 7

    def test_blank_env_falls_back_to_policy(self, monkeypatch):
        monkeypatch.setenv(GEPA_REFLECTION_MINIBATCH_ENV, "  ")
        assert resolve_reflection_minibatch_size(dataset_size=50, max_trials=10) == 10

    @pytest.mark.parametrize("bad_value", ["five", "7.5", "0", "-3"])
    def test_invalid_env_raises_naming_the_variable(self, bad_value, monkeypatch):
        # A malformed operator value must fail loudly (and at service startup,
        # see test_module_import_fails_fast_on_malformed_env), never silently
        # fall back mid-run.
        monkeypatch.setenv(GEPA_REFLECTION_MINIBATCH_ENV, bad_value)
        with pytest.raises(ValueError, match=GEPA_REFLECTION_MINIBATCH_ENV):
            resolve_reflection_minibatch_size(dataset_size=50, max_trials=10)

    def test_invalid_env_error_is_bounded(self, monkeypatch):
        # The env value is free text — a huge garbage value must not flood the
        # error message (or any log line that carries it).
        monkeypatch.setenv(GEPA_REFLECTION_MINIBATCH_ENV, "x" * 5000)
        with pytest.raises(ValueError) as exc_info:
            resolve_reflection_minibatch_size(dataset_size=50, max_trials=10)
        assert len(str(exc_info.value)) < 200

    def test_module_import_fails_fast_on_malformed_env(self, monkeypatch):
        monkeypatch.setenv(GEPA_REFLECTION_MINIBATCH_ENV, "not-a-number")
        with pytest.raises(ValueError, match=GEPA_REFLECTION_MINIBATCH_ENV):
            importlib.reload(config_module)
        monkeypatch.delenv(GEPA_REFLECTION_MINIBATCH_ENV)
        importlib.reload(config_module)

    def test_never_below_one(self, monkeypatch):
        monkeypatch.delenv(GEPA_REFLECTION_MINIBATCH_ENV, raising=False)
        assert resolve_reflection_minibatch_size(dataset_size=0, max_trials=10) == 1

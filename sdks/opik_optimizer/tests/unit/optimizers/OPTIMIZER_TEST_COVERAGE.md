# Optimizer Test Coverage

## Overview

This document describes the test coverage for optimizers in the `opik_optimizer` module.

## Test File Organization

```
tests/unit/optimizers/
├── test_base_optimizer.py           # BaseOptimizer class internals
├── test_prompt_factory.py           # prompt_overrides feature (cross-optimizer)
│
├── evolutionary/
│   ├── test_evolutionary_optimizer.py   # Main optimizer tests (init, optimize_prompt, early-stop)
│   ├── test_crossover_ops.py
│   ├── test_helpers.py
│   ├── test_mutation_ops.py
│   └── test_population_ops.py
├── few_shot/
│   ├── test_few_shot_bayesian_optimizer.py  # Main optimizer tests
│   └── test_columnar_search_space.py
├── gepa_optimizer/
│   ├── test_gepa_optimizer.py           # Main optimizer tests
│   ├── test_gepa_adapter.py
│   └── test_gepa_optimizer_validation.py
├── hierarchical/
│   ├── test_hierarchical_reflective_optimizer.py  # Main optimizer tests
│   ├── test_hierarchical_analyzer.py
│   └── test_prompt_diff.py
├── meta_prompt/
│   ├── test_meta_prompt_optimizer.py    # Main optimizer tests
│   ├── test_candidate_ops.py
│   ├── test_halloffame_ops.py
│   ├── test_meta_prompt_optimizer_synthesis.py
│   └── test_meta_prompt_optimizer_tokens.py
└── parameter/
    ├── test_parameter_optimizer.py      # Main optimizer tests + search space
    ├── test_parameter_search_space.py
    └── test_sensitivity_analysis.py
```

## Test Categories

### Root-Level Tests

| File | Purpose |
|------|---------|
| `test_base_optimizer.py` | Tests `BaseOptimizer` class methods and utilities |
| `test_prompt_factory.py` | Tests `prompt_overrides` feature across optimizers |

### Optimizer-Specific Tests

Each optimizer has a main test file (`test_<optimizer_name>.py`) that covers:

1. **Initialization** - Constructor with default and custom parameters
2. **optimize_prompt behavior** - Prompt normalization, result format
3. **Early-stop** - Skipping optimization when baseline meets threshold
4. **Input validation** - Error handling for invalid inputs

Plus additional files for algorithm-specific internals.

## Coverage Matrix

| Optimizer | Init | Prompt Norm | Early-Stop | Validation | Internals |
|-----------|:----:|:-----------:|:----------:|:----------:|:---------:|
| Evolutionary | ✅ | ✅ | ✅ | ✅ | ✅ |
| FewShotBayesian | ✅ | ✅ | ✅ | ✅ | ✅ |
| MetaPrompt | ✅ | ✅ | ✅ | ✅ | ✅ |
| Hierarchical | ✅ | ✅ | ✅ | ✅ | ✅ |
| GEPA | ✅ | ✅ | ✅ | ✅ | ✅ |
| Parameter | ✅ | N/A | ✅ | ✅ | ✅ |

**Note:** ParameterOptimizer uses `optimize_parameter` instead of `optimize_prompt`.

## Test Patterns

### Initialization Tests
```python
class TestOptimizerInit:
    def test_initialization_with_defaults(self) -> None:
        optimizer = Optimizer(model="gpt-4o")
        assert optimizer.model == "gpt-4o"
        assert optimizer.seed == 42

    def test_initialization_with_custom_params(self) -> None:
        optimizer = Optimizer(model="gpt-4o-mini", verbose=0, seed=123)
        ...
```

### optimize_prompt Tests
```python
class TestOptimizerOptimizePrompt:
    def test_single_prompt_returns_chat_prompt(self, mock_...) -> None:
        # Verify single ChatPrompt input returns ChatPrompt output
        
    def test_dict_prompt_returns_dict(self, mock_...) -> None:
        # Verify dict input returns dict output
        
    def test_invalid_prompt_raises_error(self, mock_...) -> None:
        # Verify invalid input raises ValueError/TypeError
```

### Early-Stop Tests
```python
class TestOptimizerEarlyStop:
    def test_skips_on_perfect_score(self, mock_opik_client, monkeypatch) -> None:
        optimizer = Optimizer(model="gpt-4o", perfect_score=0.95)
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)
        # Verify optimization is skipped and result indicates early stop
```

## How to Add Tests for a New Optimizer

1. **Create main test file** at `tests/unit/optimizers/<optimizer_name>/test_<optimizer_name>.py`

2. **Include standard test classes:**
   - `TestOptimizerInit` - Initialization tests
   - `TestOptimizerOptimizePrompt` - optimize_prompt behavior
   - `TestOptimizerEarlyStop` - Early-stop behavior

3. **Create additional files** for algorithm-specific internals

4. **Run tests** to verify:
   ```bash
   pytest tests/unit/optimizers/<optimizer_name>/ -v
   ```

5. **Update this coverage matrix**

## Running Tests

```bash
cd sdks/opik_optimizer

# Run all optimizer tests
pytest tests/unit/optimizers/ -v

# Run tests for a specific optimizer
pytest tests/unit/optimizers/evolutionary/ -v
pytest tests/unit/optimizers/few_shot/ -v

# Run with coverage
pytest tests/unit/optimizers/ --cov=opik_optimizer --cov-report=term-missing
```

## Related Documentation

- [test-best-practices.mdc](../../.cursor/rules/test-best-practices.mdc) - General test guidelines
- [test-organization.mdc](../../.cursor/rules/test-organization.mdc) - Test structure guidelines

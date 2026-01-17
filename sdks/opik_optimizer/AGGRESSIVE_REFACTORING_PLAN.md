# Aggressive Refactoring Plan for Opik Optimizer SDK

This document outlines aggressive refactoring opportunities to reduce file sizes and improve maintainability.

## Current State Analysis

### Largest Files (by line count)
1. **base_optimizer.py** - 2,001 lines ⚠️ **CRITICAL**
2. **evolutionary_optimizer.py** - 1,010 lines
3. **candidate_ops.py** - 941 lines
4. **display.py** - 863 lines
5. **few_shot_bayesian_optimizer.py** - 851 lines
6. **parameter_optimizer.py** - 741 lines
7. **hierarchical_reflective_optimizer.py** - 740 lines
8. **optimization_result.py** - 698 lines
9. **dataset.py** - 684 lines
10. **_llm_calls.py** - 676 lines

---

## 1. base_optimizer.py (2,001 → ~600 lines)

**Target: Split into 6-8 focused modules**

### 1.1 Extract LLM Call Tracking → `utils/llm_tracking.py`
**Lines to extract: ~285-324**
- `_reset_counters()`
- `_increment_llm_counter()`
- `_increment_llm_call_tools_counter()`
- `_add_llm_cost()`
- `_add_llm_usage()`
- `llm_call_counter`, `llm_call_tools_counter`, `llm_cost_total`, `llm_token_usage_total` attributes

**New class:**
```python
class LLMTrackingManager:
    """Manages LLM call counters, costs, and token usage."""
    def __init__(self):
        self.call_counter = 0
        self.call_tools_counter = 0
        self.cost_total = 0.0
        self.token_usage_total = {...}
    
    def reset(self): ...
    def increment_call(self): ...
    def add_cost(self, cost: float | None): ...
    def add_usage(self, usage: dict[str, Any] | None): ...
```

### 1.2 Extract Agent Management → `utils/agent_manager.py`
**Lines to extract: ~362-1017**
- `_attach_agent_owner()`
- `_setup_agent_class()`
- `_bind_optimizer_to_agent()`
- `_instantiate_agent()`
- `_extract_tool_prompts()`
- `_serialize_tools()`
- `_describe_annotation()`
- `_summarize_tool_signatures()`
- `_build_agent_config()`

**New class:**
```python
class AgentManager:
    """Handles agent instantiation, configuration, and tool extraction."""
    def setup_agent_class(self, prompt, agent_class): ...
    def instantiate_agent(self, *args, agent_class=None, **kwargs): ...
    def build_agent_config(self, prompt): ...
    def extract_tool_prompts(self, tools): ...
    def summarize_tool_signatures(self, prompt): ...
```

### 1.3 Extract Metadata Building → `utils/metadata_builder.py`
**Lines to extract: ~1040-1285**
- `get_optimizer_metadata()`
- `_build_optimizer_metadata()`
- `_build_optimization_metadata()`
- `_prepare_experiment_config()`
- `_deep_merge_dicts()` (static)

**New class:**
```python
class MetadataBuilder:
    """Builds optimization and experiment metadata."""
    def build_optimizer_metadata(self, optimizer): ...
    def build_optimization_metadata(self, optimizer, agent_class): ...
    def prepare_experiment_config(self, optimizer, prompt, dataset, ...): ...
```

### 1.4 Extract Result Building → `utils/result_builder.py`
**Lines to extract: ~764-876**
- `_select_result_prompts()`
- `_build_early_result()`
- `_build_final_result()`

**New class:**
```python
class ResultBuilder:
    """Builds OptimizationResult objects from context and history."""
    def build_early_result(self, context, history_state, ...): ...
    def build_final_result(self, context, history_state, ...): ...
    def select_result_prompts(self, context, ...): ...
```

### 1.5 Extract Display/UI Logic → `utils/optimizer_display.py`
**Lines to extract: ~1374-1430**
- `_display_header()`
- `_display_configuration()`
- `_display_baseline_evaluation()`
- `_display_final_result()`

**New class:**
```python
class OptimizerDisplay:
    """Handles display of optimization progress and results."""
    def display_header(self, optimization_id): ...
    def display_configuration(self, context): ...
    def display_baseline_evaluation(self, baseline_score): ...
    def display_final_result(self, result): ...
```

### 1.6 Extract Validation → `utils/validation.py`
**Lines to extract: ~920-968**
- `_validate_optimization_inputs()`

**New module:**
```python
def validate_optimization_inputs(
    prompt, dataset, metric, support_content_parts=False
): ...
```

### 1.7 Extract Context Setup → `utils/context_setup.py`
**Lines to extract: ~453-544**
- `_setup_optimization()` (large method, ~90 lines)
- `_create_optimization_run()`
- `_select_evaluation_dataset()`
- `_normalize_prompt_input()`

**New class:**
```python
class ContextSetup:
    """Sets up OptimizationContext for optimization runs."""
    def setup_optimization(self, optimizer, prompt, dataset, ...): ...
    def create_optimization_run(self, optimizer, dataset, metric, ...): ...
    def normalize_prompt_input(self, prompt): ...
```

### 1.8 Keep in base_optimizer.py (~600 lines)
- Core `BaseOptimizer` class structure
- `OptimizationContext` dataclass
- `AlgorithmResult` dataclass
- Main optimization orchestration (`optimize_prompt()`, `run_optimization()`)
- Evaluation logic (`evaluate()`, `evaluate_prompt()`)
- History management delegation
- Stop condition logic (`_should_stop_context()`)

---

## 2. optimization_result.py (698 → ~400 lines)

### 2.1 Extract History State → `optimization_result/history_state.py`
**Lines to extract: ~127-473**
- `OptimizationHistoryState` class (entire class, ~350 lines)

**New file structure:**
```
optimization_result/
  __init__.py
  history_state.py  # OptimizationHistoryState
  models.py          # OptimizerCandidate, OptimizationTrial, OptimizationRound
  result.py          # OptimizationResult
  builders.py        # build_candidate_entry, first_trial_index
```

### 2.2 Extract Models → `optimization_result/models.py`
**Lines to extract: ~29-125**
- `OptimizerCandidate` dataclass
- `OptimizationTrial` dataclass
- `OptimizationRound` dataclass

### 2.3 Extract Builders → `optimization_result/builders.py`
**Lines to extract: ~475-507**
- `first_trial_index()`
- `build_candidate_entry()`

### 2.4 Keep in result.py (~200 lines)
- `OptimizationResult` class
- Display methods (`__str__()`, `__rich__()`, `display()`)
- Helper methods (`get_run_link()`, `get_optimized_*()`)

---

## 3. display.py (863 → ~400 lines)

### 3.1 Extract Prompt Formatting → `utils/display/prompt_formatting.py`
**Lines to extract: ~30-100**
- `format_prompt_for_plaintext()`
- `format_prompt_snippet()` (if exists)

### 3.2 Extract Number Formatting → `utils/display/number_formatting.py`
**Lines to extract: ~23-77**
- `format_float()`
- `safe_percentage_change()`

### 3.3 Extract Rich Rendering → `utils/display/rich_rendering.py`
**Lines to extract: ~200-400** (estimate)
- All Rich-specific rendering functions
- `render_rich_result()`
- `build_rich_summary()`

### 3.4 Extract Plaintext Rendering → `utils/display/plaintext_rendering.py`
**Lines to extract: ~100-200** (estimate)
- `build_plaintext_summary()`
- Plaintext-specific formatting

### 3.5 Keep in display.py (~200 lines)
- Main entry points
- Re-exports from submodules
- Common utilities

**New structure:**
```
utils/display/
  __init__.py
  prompt_formatting.py
  number_formatting.py
  rich_rendering.py
  plaintext_rendering.py
  selection_policy.py  # summarize_selection_policy
```

---

## 4. Algorithm-Specific Refactorings

### 4.1 few_shot_bayesian_optimizer.py (851 → ~500 lines)

**Extract to `few_shot_bayesian_optimizer/helpers/`:**
- Example selection logic → `example_selection.py`
- Columnar search space logic → Already in `ops/columnarsearch_ops.py` ✅
- Optuna integration → `optuna_integration.py`

**Extract to `few_shot_bayesian_optimizer/ops/`:**
- Candidate generation → `candidate_generation.py`
- Evaluation batching → `evaluation_batching.py`

### 4.2 evolutionary_optimizer.py (1,010 → ~600 lines)

**Already has `ops/` directory - extract more:**
- Population initialization → `ops/population_init.py`
- Fitness calculation → `ops/fitness_calc.py`
- Selection strategies → `ops/selection_strategies.py`
- Main loop logic → Keep in main file

### 4.3 candidate_ops.py (941 → ~500 lines)

**Split by operation type:**
```
ops/
  candidate_ops/
    __init__.py
    generation.py      # Candidate generation
    evaluation.py      # Candidate evaluation
    selection.py       # Candidate selection
    transformation.py  # Candidate transformations
```

### 4.4 parameter_optimizer.py (741 → ~500 lines)

**Extract to `parameter_optimizer/ops/`:**
- Search space definition → `search_space.py`
- Sensitivity analysis → Already in `ops/sensitivity_ops.py` ✅
- Parameter search → Already in `ops/search_ops.py` ✅
- Result extraction → `result_extraction.py`

---

## 5. Additional Refactoring Opportunities

### 5.1 dataset.py (684 lines)
**Split by dataset type:**
```
datasets/
  __init__.py
  base.py           # Base dataset utilities
  evaluation.py     # Evaluation dataset helpers
  validation.py     # Validation dataset helpers
  splits.py         # Dataset splitting logic
```

### 5.2 _llm_calls.py (676 lines)
**Already well-structured, but consider:**
- Extract rate limiting logic → `utils/rate_limiting.py`
- Extract response parsing → `utils/llm_response_parser.py`
- Keep core `call_model()` in main file

---

## 6. Implementation Strategy

### Phase 1: Critical Refactorings (High Impact)
1. ✅ **base_optimizer.py** - Extract LLM tracking, agent management, metadata building
2. ✅ **optimization_result.py** - Split into submodules
3. ✅ **display.py** - Split into display submodules

**Expected reduction: ~1,500 lines from largest files**

### Phase 2: Algorithm-Specific Refactorings
1. ✅ **few_shot_bayesian_optimizer.py** - Extract helpers
2. ✅ **evolutionary_optimizer.py** - Extract more ops
3. ✅ **candidate_ops.py** - Split by operation type

**Expected reduction: ~800 lines**

### Phase 3: Utility Refactorings
1. ✅ **dataset.py** - Split by functionality
2. ✅ **parameter_optimizer.py** - Extract more ops

**Expected reduction: ~400 lines**

---

## 7. File Size Targets

| File | Current | Target | Reduction |
|------|---------|--------|-----------|
| base_optimizer.py | 2,001 | 600 | -70% |
| optimization_result.py | 698 | 400 | -43% |
| display.py | 863 | 400 | -54% |
| few_shot_bayesian_optimizer.py | 851 | 500 | -41% |
| evolutionary_optimizer.py | 1,010 | 600 | -41% |
| candidate_ops.py | 941 | 500 | -47% |
| parameter_optimizer.py | 741 | 500 | -33% |
| **Total** | **7,105** | **3,500** | **-51%** |

---

## 8. Benefits

1. **Improved Maintainability**: Smaller, focused files are easier to understand and modify
2. **Better Testability**: Isolated components are easier to test
3. **Reduced Cognitive Load**: Developers can focus on specific concerns
4. **Easier Code Reviews**: Smaller files make reviews more manageable
5. **Better Reusability**: Extracted utilities can be reused across optimizers
6. **Clearer Dependencies**: Explicit imports show what each module depends on

---

## 9. Migration Notes

### Backward Compatibility
- All public APIs remain unchanged
- Internal refactoring only
- Use `__init__.py` to maintain import paths

### Testing Strategy
1. Run full test suite after each extraction
2. Verify no behavior changes
3. Check import paths still work
4. Validate optimizer outputs match

### Incremental Approach
- Extract one module at a time
- Commit after each successful extraction
- Run tests between extractions
- Update documentation as needed

---

## 10. Example: base_optimizer.py Refactoring

### Before:
```python
# base_optimizer.py (2,001 lines)
class BaseOptimizer(ABC):
    def _reset_counters(self): ...
    def _increment_llm_counter(self): ...
    def _add_llm_cost(self, cost): ...
    def _setup_agent_class(self, prompt, agent_class): ...
    def _build_agent_config(self, prompt): ...
    def _build_optimizer_metadata(self): ...
    # ... 70+ more methods
```

### After:
```python
# base_optimizer.py (~600 lines)
from .utils.llm_tracking import LLMTrackingManager
from .utils.agent_manager import AgentManager
from .utils.metadata_builder import MetadataBuilder
from .utils.result_builder import ResultBuilder

class BaseOptimizer(ABC):
    def __init__(self, ...):
        self._llm_tracker = LLMTrackingManager()
        self._agent_manager = AgentManager(self)
        self._metadata_builder = MetadataBuilder(self)
        self._result_builder = ResultBuilder(self)
    
    # Core optimization logic only
    def optimize_prompt(self, ...): ...
    def run_optimization(self, ...): ...
    def evaluate(self, ...): ...
```

---

## 11. Next Steps

1. **Review this plan** with the team
2. **Prioritize** which refactorings to tackle first
3. **Create tickets** for each phase
4. **Start with Phase 1** (highest impact)
5. **Measure progress** with line count reductions
6. **Update documentation** as modules are extracted

---

## 12. Notes

- All FIXME comments in code should be addressed during refactoring
- Consider creating a `utils/` subdirectory structure for better organization
- Some optimizers already have good `ops/` structures - use as templates
- Maintain existing test coverage during refactoring
- Update `.cursor/rules/` documentation after major refactorings

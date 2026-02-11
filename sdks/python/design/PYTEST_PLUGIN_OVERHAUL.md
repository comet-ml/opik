# Pytest Plugin Overhaul Plan

## Objective

Evolve the current `llm_unit` pytest integration from pass/fail tracking into a CI-ready
agent testing workflow that supports simulation episodes, trajectory checks, and budget gates.

## Current Baseline

- Decorator-based test tracking (`@llm_unit`)
- Automatic test experiment creation
- Pass/fail score logging
- Dataset linkage for test inputs and expected outputs

## Gaps

- No first-class episode object for multi-turn agent tests
- No structured budget checks (turn count, tool-call count, latency, token usage)
- No standard trajectory assertions/rubric contract
- Limited configurability (partially addressed in this branch)

## Phased Plan

### Phase 1: Reliability and Operability (in progress)

- Harden pytest plugin lifecycle and storage handling
- Improve async compatibility
- Add routing/naming settings for dataset, experiment, and score names
- Bring Python SDK and Fern docs in sync with runtime behavior
- Add focused unit coverage for plugin behavior

### Phase 2: Episode Contract

Introduce a lightweight episode result schema consumable in pytest:

```python
{
  "scenario_id": "refund_flow_v1",
  "thread_id": "...",
  "assertions": [...],
  "scores": [...],
  "budgets": {
    "max_turns": 5,
    "tool_calls": {"used": 3, "limit": 4},
    "latency_ms": {"p95": 1200, "limit": 2000}
  },
  "trajectory_summary": {...}
}
```

### Phase 3: Simulation + Trajectory Utilities

- Add test helpers/fixtures around `run_simulation` for deterministic episodes
- Add reference assertions for:
  - schema constraints
  - policy checks
  - tool-call limits
  - trajectory quality thresholds

### Phase 4: CI Experience

- Support concise terminal summary with failing scenarios and budget violations
- Provide deterministic output artifacts suitable for PR comments
- Publish migration guidance from `@llm_unit` to episode-based patterns

## Non-Goals

- Replacing `llm_unit` immediately
- Breaking compatibility for existing users

## Migration Strategy

- Keep `llm_unit` as stable compatibility layer
- Introduce new episode helpers incrementally
- Provide opt-in rollout with examples and templates

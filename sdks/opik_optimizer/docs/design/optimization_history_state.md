# Optimization History State — Candidate-First Design PRD

## Summary
A uniform, stateful history pipeline for all optimizers. Replace per-optimizer dict wiring with a candidate-first API that records rounds/trials through a shared `OptimizationHistoryState`, plus a small candidate helper. BaseOptimizer exposes ergonomic hooks; optimizers only supply algorithm-specific extras.

## Motivation / Problems Today
- Inconsistent history shapes (dicts vs `OptimizationRound/Trial`, missing fields, differing keys).
- Repeated wiring of `round_index/trial_index/timestamps/best_so_far/stop_reason`.
- Prompt-centric assumptions don’t fit tool-only or parameter-only runs.
- Budget/stop flags not stamped uniformly across optimizers.
- Metadata (pareto, params, failure modes) added ad hoc, making consumers brittle.

## Goals
- Single source of truth for history emission (rounds/trials/candidates).
- Candidate-first API that works for prompts, parameter sets, tool bundles, multi-agent dicts.
- Auto-stamp common fields (indices, timestamps, best_so_far, stop reason) without per-optimizer code.
- Backward-compatible output shape (normalized dicts) with a clear migration path.

Non-goals (for now)
- No streaming/resume yet; no schema version bump beyond current normalized dicts.
- No threading primitives; assume calls are made from optimizer threads.

## Proposed Architecture
- `OptimizationHistoryState` (evolved builder) holds:
  - Reference to `OptimizationContext` (optimization_id, project_name, dataset info, trials_completed, finish_reason).
  - Running best_so_far and in-flight round buffers.
  - Methods: `start_round`, `record_trial` (candidate-first), `end_round`, `finalize_stop`, `get_entries`.
- Candidate helper:
  - `build_candidate_entry(prompt_or_payload, score=None, id=None, metrics=None, notes=None, tools=None, extra=None)` → normalized dict usable for both trials and round candidates.
- BaseOptimizer hooks (ergonomics, delegate to state):
  - `begin_round(**extras)`, `on_candidate_start(candidate, round_handle)`, `on_candidate_result(candidate_handle, score, metrics=None, extras=None)`, `finish_round(...)`.
  - Hooks are thin wrappers over state; optimizers may call state directly for fine-grained control.
- Data model (emitted as dicts; OptimizerCandidate is the normalized spec):
  - `OptimizerCandidate`: `{"id"?, "candidate": <payload>, "score"?, "metrics"?, "notes"?, "tools"?, "extra"?}`
    - `candidate` payload is either a ChatPrompt, a dict of ChatPrompts (multi-agent), or another structured object (e.g., params); tools/params can also be embedded in the ChatPrompt.
  - Trial: `{trial_index, score, candidate: OptimizerCandidate | raw payload, metrics?, dataset?, extra?, timestamp}`
    - One candidate per trial.
  - Round: `{round_index, trials: [...], candidates?: list[OptimizerCandidate], best_score?, best_candidate?, stop_reason?, stopped, best_so_far?, timestamp, extra?}`
  - The helper can wrap a prompt/dict prompt into an OptimizerCandidate for uniformity; direct payloads remain allowed during migration.

## API Spec (state)
- `start_round(round_index: int | None = None, extras: dict | None = None) -> RoundHandle`
  - Auto assigns round_index if None; stamps timestamp; copies context ids/finish_reason if present.
  - `record_trial(handle, *, score: float | None, candidate: Any | None = None, metrics: dict | None = None, dataset: str | None = None, extras: dict | None = None, candidates: list[dict] | None = None) -> TrialHandle`
    - Uses `context.trials_completed` for trial_index; updates best_so_far internally.
    - `candidate` is the raw payload (ChatPrompt, dict-of-prompts, params); optional `candidates` accepts normalized candidate specs to avoid duplicate wrapping.
  - `end_round(handle, *, best_score: float | None = None, best_candidate: Any | None = None, stop_reason: str | None = None, extras: dict | None = None, candidates: list[dict] | None = None) -> dict`
    - Flushes buffered trials/candidates into history; stamps stop flags/timestamp; returns normalized entry.
- `finalize_stop(stop_reason=None)` stamps finish_reason/stop_reason on the last entry if missing.
- `get_entries() -> list[dict]` returns normalized history; still supports legacy `append_entry` during migration.

## BaseOptimizer Hook API (uniform start/stop naming)
- `begin_round(**extras) -> RoundHandle` (delegates to state.start_round)
- `finish_round(round_handle, *, best_score=None, best_candidate=None, stop_reason=None, extras=None, candidates=None)` (delegates to state.end_round)
- Candidate hooks (optional sugar):
  - `start_candidate(candidate, round_handle) -> CandidateHandle` (pre-hook; can simply return the candidate or a handle)
  - `finish_candidate(candidate_handle, *, score, metrics=None, extras=None, candidates=None)` (delegates to state.record_trial)
  - These pre/post names keep start/finish symmetry with rounds; optimizers may skip them and call `record_trial` directly.

## Lifecycle (candidate-first)
```
round = begin_round(...)
for cand in generated_candidates:
    h = on_candidate_start(cand, round)
    score, metrics = evaluate(cand)
    on_candidate_result(h, score=score, metrics=metrics, extras={...})
finish_round(round, best_score=..., best_candidate=..., stop_reason=maybe)
finalize_stop()  # after loop
```

## ASCII Architecture Diagram
```
                           +---------------------------+
                           |   BaseOptimizer hooks    |
                           |---------------------------|
                           | begin_round / finish_round|
                           | start_candidate / finish_candidate|
                           +--------------+------------+
                                          |
                                          v
                           +---------------------------+
                           | OptimizationHistoryState  |
                           |---------------------------|
                           | context (ids, counters)   |
                           | start_round               |
                           | record_trial (candidate)  |
                           | end_round                 |
                           | finalize_stop             |
                           | get_entries               |
                           +--------------+------------+
                                          |
                                          v
                           +---------------------------+
                           | Normalized History        |
                           |---------------------------|
                           | Round (OptimizerRound)    |
                           |  - round_index            |
                           |  - trials[]               |
                           |  - candidates[]?          |
                           |  - best_score/best_candidate|
                           |  - stop_reason/stopped    |
                           |  - timestamp              |
                           |  - extra (pareto_front,   |
                           |    selection_meta, best_so_far,|
                           |    failure_modes, params...) |
                           |                           |
                           | Trial (OptimizerTrial)    |
                           |  - trial_index            |
                           |  - score                  |
                           |  - candidate (OptimizerCandidate|
                           |    or raw payload)        |
                           |  - metrics/dataset/extra  |
                           |  - timestamp              |
                           |                           |
                           | OptimizerCandidate        |
                           |  - id?                    |
                           |  - candidate payload      |
                           |    (ChatPrompt | dict     |
                           |     of ChatPrompts | other)|
                           |  - score?/metrics?        |
                           |  - notes?                 |
                           |  - extra (parents, origin,|
                           |    ops, components, params|
                           |    hall_of_fame_ref...)   |
                           |    ChatPrompt (when used):|
                           |      name?, messages[],   |
                           |      model_kwargs?,       |
                           |      metadata?            |
                           +---------------------------+

                 (feeds)
                           +---------------------------+
                           | AlgorithmResult          |
                           | OptimizationResult       |
                           +---------------------------+
```

## Nested Types (spec and stacking)
```
OptimizationResult
  history: list[OptimizerRound]
    OptimizerRound
      round_index: int
      trials: list[OptimizerTrial]
      candidates?: list[OptimizerCandidate]
      best_score?: float
      best_candidate?: Any (payload or OptimizerCandidate)
      stop_reason?: str
      stopped: bool
      best_so_far?: float
      timestamp?: str
      extra?: dict
        OptimizerTrial
          trial_index: int
          score: float | None
          candidate: OptimizerCandidate | Any
          metrics?: dict
          dataset?: str
          extra?: dict
          timestamp?: str
        OptimizerCandidate
          id?: str
          candidate: ChatPrompt | dict[str, ChatPrompt] | Any payload
          score?: float
          metrics?: dict
          notes?: str
          extra?: dict  # lineage/ops/components/params/etc.
            # Common extras:
            # - parents: list[str] (lineage)
            # - origin: str ("fresh" | "mutation" | "crossover" | "gepa_seed" | ...)
            # - ops: list[str] (mutation/crossover operators)
            # - components: dict (GEPA parts, template pieces)
            # - params: dict (parameter sets)
            # - hall_of_fame_ref: bool/id (if pulled from HoF)
            ChatPrompt (when used)
              name?: str
              messages: list[dict]  # each with role/content/tools/etc.
                message.role: "system" | "user" | "assistant" | ...
                message.content: str | list[content parts]
                message.tools?: list[...]  # optional tool definitions
              model_kwargs?: dict
              metadata?: dict

Pareto & Lineage (where to store):
- Per-trial: put full objective vectors in `OptimizerTrial.metrics` (e.g., {"primary": x, "length": y}).
- Per-candidate: use `OptimizerCandidate.id` for stable handles and `extra.parents` / `extra.origin` / `extra.ops` for lineage (e.g., mutation/crossover, GEPA source/components).
- Per-round: add `round.extra.pareto_front` for snapshots (list of {id, metrics, candidate_ref?}) and `round.extra.selection_meta` for survivor/selection details.
    - Example `round.extra` keys:
      - pareto_front: list[{id, metrics, candidate_ref?}]
      - selection_meta: {method: "NSGA-II"|"tournament"|..., survivors: [id...]}
      - best_so_far?: float
      - stopped?: bool
      - failure_modes?: list[str] (HRO)
      - parameters_used?: dict (Optuna/parameter search)
```


## Backward Compatibility / Migration
- Keep `append_entry` temporarily; mark deprecated once all optimizers migrated.
- Emit the same normalized dict shape consumed today; no consumer change expected.
- Migration order: Evo → MetaPrompt → GEPA → HRO → Few-Shot/Parameter → remaining. Replace manual `OptimizationRound/Trial` construction with start/record/end.

## Pros / Cons
Pros:
- Consistent fields/timestamps/stop flags; fewer bugs from missed wiring.
- Candidate-first works for prompts, params, tools, and bundles.
- Simplifies adding new metadata (e.g., pareto snapshots, params) in one place.
- Hooks make optimizer code shorter and easier to read.
Cons/Risks:
- Small learning curve for new API.
- Needs careful migration to avoid double-logging (state vs legacy append_entry).
- Not thread-safe by default; parallel emitters would need coordination.

## Alternatives Considered
- Keep current builder + append_entry: low effort, but continues inconsistency.
- Only helper functions (no state): reduces some duplication but still forces optimizers to manage counters/timestamps.
- Full event bus/observer: overkill for current scope, more moving parts.

## Testing Plan
- Unit tests for state API: ordering, best_so_far updates, stop propagation, candidate=None/tool/param payloads, stop stamping.
- Optimizer smoke tests: one per category to assert history entries are produced via state and contain required fields.
- Backward-compat test ensuring append_entry still works during migration.

## Rollout Plan
1) Implement `OptimizationHistoryState` + candidate helper + Base hooks; wire Base to construct/clear state and consume `get_entries()`.
2) Migrate Evo/MetaPrompt/GEPA/HRO/Few-shot/Parameter to start/record/end + candidate helper.
3) Deprecate/remove legacy `append_entry` uses; clean up docs/types.
4) Update docs to describe candidate-first terminology (trial=eval of candidate; round=iteration).

## Open Questions
- Thread safety: do we need optional locking for parallel emitters?
- Should `best_candidate` be stored by reference or copied (for large prompts)?
- Do we want per-candidate fingerprints now or defer to a later schema bump?

## Thread Safety (future option)
- Current design assumes history emission happens on the optimizer thread; evaluation parallelism (n_threads) is contained inside the evaluator/adapter.
- If we start emitting trials from worker threads, add an optional lock in `OptimizationHistoryState` around start/record/end, or collect per-thread buffers and merge under a lock in `end_round`.
- TODO in code: place hook/flag for an optional `thread_safe=True` mode that wraps internal mutations in a lock; ensure `context.trials_completed` increments remain atomic when used across threads.

# Scoring Pipeline & Convergence Proposals

## How scoring works end-to-end

### Layer 1: Assertion Score (lowest level)

Each dataset item has multiple **assertions** (e.g., "Response shows empathy",
"Response offers a concrete resolution"). Each assertion produces a score:
- Binary: `1.0` (pass) or `0.0` (fail)
- Evaluated by an LLM judge (gpt-5-nano in our tests)

### Layer 2: Run Score

A single evaluation run produces scores for all assertions on one item:

```
run_score = mean(assertion_scores)
```

Example with 3 assertions scoring `[1.0, 0.0, 1.0]` → run_score = `0.667`

When `runs_per_item > 1`, the same item is evaluated multiple times.

### Layer 3: Item Score (what GEPA sees)

```
item_score = mean(run_scores)    # averaged across all runs
```

With 3 runs scoring `[0.667, 1.0, 0.667]` → item_score = `0.778`

This is a **continuous float** between 0 and 1.

### Layer 4: Minibatch Gate (sum comparison)

GEPA evaluates the **parent** and **child** on the same minibatch (4 items),
then compares raw sums:

```python
old_sum = sum(parent_scores_on_minibatch)   # e.g., 3.867
new_sum = sum(child_scores_on_minibatch)    # e.g., 3.800

if new_sum > old_sum:    # strict inequality
    → proceed to full eval
else:
    → REJECT (child never gets a full evaluation)
```

This is the main bottleneck for convergence.

### Layer 5: Trial Score (what users see)

```
pass_rate = items_passed / items_total
```

An item "passes" only if **all assertions pass** in at least one run (binary).
This is the number displayed in the UI and used as the optimization target.

### The scoring mismatch

| Level | Score type | Range | Used by |
|---|---|---|---|
| Minibatch gate | `sum(mean_assertions)` | 0 to N | GEPA acceptance |
| Full eval / trial | `pass_rate` (binary per item) | 0.0 to 1.0 | UI, optimizer |

A child can improve pass_rate (e.g., from 0.80 to 1.00) but get rejected
by the minibatch gate because `sum(continuous_scores)` decreased slightly.

---

## Why mutations get rejected incorrectly

### Problem 1: LLM judge non-determinism

The assertion evaluator (gpt-5-nano) is non-deterministic. The same prompt
on the same item can score differently across evaluations:

```
Parent evaluated on item A:  assertions = [1.0, 1.0, 1.0] → 1.0
Child  evaluated on item A:  assertions = [1.0, 0.0, 1.0] → 0.667
```

The child's prompt may be objectively better, but the LLM judge happened
to score one assertion differently. With 4 items in the minibatch, a single
flipped assertion can swing the sum by 0.33, which is enough to fail the gate.

### Problem 2: Small minibatch → high variance

With only 4 items, the gate compares sums in the range [0, 4].
A single item scoring 0.33 lower flips the entire comparison.

Statistical argument: if each item score has variance σ² due to LLM noise,
the sum over 4 items has variance 4σ². The signal (actual improvement from
the mutation) needs to exceed √(4σ²) ≈ 2σ to reliably pass the gate.
For small improvements (e.g., fixing 1 out of 5 items), the signal-to-noise
ratio is too low.

### Problem 3: Score mismatch — continuous gate, binary target

The minibatch gate uses `sum(continuous_item_scores)` but the optimization
target is `pass_rate` (binary per item).

Example:
- Parent: item scores `[0.8, 0.9, 1.0, 0.7]` → sum = 3.4
- Child:  item scores `[1.0, 1.0, 1.0, 0.5]` → sum = 3.5 → passes gate

But in pass_rate terms:
- Parent: 1/4 items fully pass = 0.25
- Child:  3/4 items fully pass = 0.75

The reverse is also true — a child could improve pass_rate but have
a lower continuous sum.

### Problem 4: Parent re-evaluated on different random seed

The parent's minibatch scores are re-evaluated fresh (not cached), so
even the parent's own scores fluctuate between iterations. This means
the gate baseline itself is noisy.

---

## Proposals

### Proposal 1: Accept within tolerance (epsilon-acceptance)

**Change:** Accept if `new_sum >= old_sum - epsilon`

```python
# Current
if new_sum > old_sum: accept()

# Proposed
epsilon = 0.1 * len(minibatch)  # 10% tolerance per item
if new_sum >= old_sum - epsilon: accept()
```

**Why it helps:** Absorbs LLM judge noise. A mutation that's "roughly as good"
on the minibatch still gets a full eval, where the larger sample size
reduces noise.

**Tradeoff:** More full evals (slower, more expensive), but fewer
false rejections. The full eval is the real quality check — the minibatch
gate is just a cheap pre-filter.

**Implementation:** In GEPA's engine.py or via a wrapper around the
adapter's evaluate() method.

### Proposal 2: Cache parent minibatch scores

**Change:** Don't re-evaluate the parent on the minibatch. Use cached scores
from the most recent full evaluation.

```python
# Current: evaluate parent fresh on minibatch
eval_curr = adapter.evaluate(minibatch, parent)

# Proposed: use cached scores from last full eval
cached_scores = [parent_full_eval_scores[item_id] for item_id in minibatch_ids]
```

**Why it helps:** Eliminates noise from re-evaluating the parent. The gate
now compares `child_fresh_scores vs parent_known_scores`, removing one
source of variance.

**Tradeoff:** Parent scores may be stale if the LLM judge is version-updated,
but in practice evaluations happen within minutes of each other.

**Implementation:** Store per-item scores from the last full eval in the
adapter, look them up instead of re-evaluating.

### Proposal 3: Use pass_rate for the gate (align metrics)

**Change:** Instead of `sum(continuous_scores)`, count how many items
fully pass in the minibatch.

```python
# Current
gate_score = sum(item_scores)  # continuous, noisy

# Proposed
gate_score = sum(1 for s in item_scores if s >= 1.0)  # binary, matches target
```

**Why it helps:** Eliminates the scoring mismatch. If a mutation makes 3/4
minibatch items fully pass (vs 2/4 for parent), it should always be accepted —
even if continuous scores are slightly lower on the 4th item.

**Tradeoff:** Less granular signal — a mutation that improves assertion
scores from 0.5 to 0.9 (but not to 1.0) would look identical to one
that doesn't improve at all. Could slow early convergence when no items
fully pass yet.

**Variant:** Hybrid: use pass_rate as primary, continuous sum as tiebreaker.

### Proposal 4: Skip the minibatch gate entirely

**Change:** Always run full evaluation for every mutation.

```python
# Current flow:
#   propose → minibatch eval → gate → [full eval if passes]

# Proposed flow:
#   propose → full eval → compare against best
```

**Why it helps:** Eliminates all gate-related false rejections. Every
mutation gets a fair shot on the full dataset.

**Tradeoff:** ~3x more expensive per iteration (full eval = 5 items vs
minibatch = 4 items in our case). With only 5 items, the minibatch
saves almost nothing anyway. For larger datasets (50+ items), this
becomes prohibitively expensive.

**When to use:** When `dataset_size <= 10` — the minibatch gate saves
almost no compute but adds significant noise.

### Proposal 5: Increase minibatch size

**Change:** Use `minibatch_size = max(dataset_size, some_minimum)` when
the dataset is small.

```python
# Current: minibatch_size = 4 (fixed)
# Proposed: minibatch_size = min(dataset_size, max(4, dataset_size * 0.8))
```

**Why it helps:** With 5 items, a minibatch of 4 means we're missing
only 1 item — might as well evaluate all 5. With 50 items, a minibatch
of 40 still saves 20% compute while reducing noise.

**Tradeoff:** Similar to Proposal 4 for small datasets. Helps less
for large datasets.

### Proposal 6: Best-of-N gate evaluation

**Change:** Run the minibatch evaluation N times (e.g., 3), accept if
the child wins at least once.

```python
# Evaluate child on minibatch 3 times
wins = 0
for _ in range(3):
    child_scores = evaluate(minibatch, child)
    if sum(child_scores) > sum(parent_scores):
        wins += 1

if wins >= 1:  # child can be better
    accept()
```

**Why it helps:** Reduces false rejections from LLM noise. If a mutation
is genuinely better, it'll win at least 1 out of 3 times.

**Tradeoff:** 3x more minibatch evaluations. But minibatch evals are
cheap relative to full evals.

### Proposal 7: Statistical significance gate

**Change:** Instead of comparing sums, run a paired test on per-item scores.

```python
from scipy.stats import wilcoxon

# Compare child vs parent on each minibatch item
diffs = [child_scores[i] - parent_scores[i] for i in range(len(minibatch))]
stat, p_value = wilcoxon(diffs, alternative='greater')

if p_value < 0.3:  # weak evidence of improvement is enough
    accept()
```

**Why it helps:** Accounts for variance and per-item pairing. A mutation
that improves 3/4 items slightly but regresses 1 item would still pass.

**Tradeoff:** Requires enough items for the test to be meaningful.
With 4 items, statistical power is very low. Better suited for larger
minibatches (10+).

---

## Recommendation

For our use case (5-30 items, single metric, product feature):

| Priority | Proposal | Effort | Impact |
|---|---|---|---|
| **1** | Skip minibatch gate when dataset ≤ 10 | Low | High |
| **2** | Cache parent scores | Low | Medium |
| **3** | Accept within tolerance (epsilon) | Low | Medium |
| **4** | Align gate metric with pass_rate | Medium | High |

**Proposal 1 + 2 combined** is the quickest win: for small datasets,
just do full eval every time (skipping the noisy gate), and for larger
datasets, at least cache the parent scores to remove half the noise.

Proposal 4 (align metrics) is the most principled fix but requires
changes to how GEPA processes scores.

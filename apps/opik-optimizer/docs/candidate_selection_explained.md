# Candidate Selection Strategies in GEPA

## Overview

After each iteration, GEPA must pick which candidate to mutate next.
The choice of **parent** determines the starting point for the reflection LLM —
a weak parent produces a child that starts from a weaker baseline.

GEPA offers three strategies: `"pareto"` (default), `"current_best"`, and `"epsilon_greedy"`.

---

## Strategy 1: Pareto Selection (default)

### How it works

GEPA maintains a **per-example Pareto front** — for each validation example,
it tracks which candidate(s) scored highest on that specific example.

When selecting a parent:

1. For each validation example, find which candidates are on the Pareto front (best scorers)
2. Count how many front positions each candidate holds (its "frequency")
3. Build a sampling list where each candidate appears `frequency` times
4. Pick randomly from this list

### Concrete example

Suppose we have **5 validation examples** and **4 candidates** after a few iterations:

| | Example 1 | Example 2 | Example 3 | Example 4 | Example 5 | **Overall** |
|---|---|---|---|---|---|---|
| Candidate 0 (seed) | 0.5 | 0.5 | 0.5 | 0.5 | 0.5 | **0.50** |
| Candidate 1 | 0.8 | 0.8 | 0.8 | 0.8 | 0.8 | **0.80** |
| Candidate 2 | 0.3 | 0.3 | 0.3 | **1.0** | 0.3 | **0.44** |
| Candidate 3 | 0.6 | 0.6 | **1.0** | 0.6 | **1.0** | **0.76** |

**Step 1 — Build Pareto front per example:**

| Example | Best score | Front members |
|---|---|---|
| Example 1 | 0.8 | {Candidate 1} |
| Example 2 | 0.8 | {Candidate 1} |
| Example 3 | 1.0 | {Candidate 3} |
| Example 4 | 1.0 | {Candidate 2} |
| Example 5 | 1.0 | {Candidate 3} |

**Step 2 — Count front positions (frequency):**

| Candidate | Front positions | Frequency |
|---|---|---|
| Candidate 0 | none | 0 |
| Candidate 1 | Examples 1, 2 | 2 |
| Candidate 2 | Example 4 | 1 |
| Candidate 3 | Examples 3, 5 | 2 |

**Step 3 — Build sampling list:**

```
[Candidate 1, Candidate 1, Candidate 2, Candidate 3, Candidate 3]
```

**Step 4 — Random pick:**

| Candidate | Selection probability | Overall score |
|---|---|---|
| Candidate 1 | 2/5 = **40%** | **0.80** (best!) |
| Candidate 2 | 1/5 = **20%** | **0.44** (worst!) |
| Candidate 3 | 2/5 = **40%** | **0.76** |

### The problem

Candidate 2 has the **worst overall score** (0.44) but gets **20% selection probability**
because it happens to be the best on Example 4. When Candidate 2 is selected as parent:

1. The reflection LLM sees its weak prompt as the starting point
2. Even if the mutation improves it (say from 0.44 to 0.55), the child is still
   much worse than Candidate 1 (0.80)
3. The child passes the minibatch acceptance gate (0.55 > 0.44 on the sampled items)
4. The child gets added to the pool, further diluting selection probability for Candidate 1

As the pool grows with niche candidates, the best candidate's selection probability
keeps dropping. With 10 candidates each holding 1 front position, the best candidate
(holding 2 positions) only has 2/12 ≈ 17% probability.

### When Pareto selection is good

- Multi-objective optimization (different objectives need different candidates)
- Early exploration when you don't know which direction is best
- Large diverse datasets where different examples need fundamentally different approaches

### When Pareto selection hurts

- Single-metric optimization where you want the best overall score
- Small datasets (5-10 items) where niche candidates inflate the front
- Later iterations when the algorithm should be exploiting, not exploring

---

## Strategy 2: Current Best

### How it works

Always picks the candidate with the highest **overall validation score**.

```
parent = argmax(candidate.full_valset_score for candidate in pool)
```

### Using the same example

| Candidate | Overall score | Selected? |
|---|---|---|
| Candidate 1 | **0.80** | Always selected |
| Candidate 3 | 0.76 | Never selected |
| Candidate 2 | 0.44 | Never selected |

Selection probability: Candidate 1 = **100%**, everyone else = **0%**.

### Pros
- Fastest convergence — always improves from the best starting point
- No wasted iterations on mediocre parents

### Cons
- Gets stuck in local optima — if Candidate 1 has reached its ceiling,
  the algorithm keeps trying to improve it with diminishing returns
- No exploration of alternative approaches

---

## Strategy 3: Epsilon-Greedy

### How it works

With probability `(1 - epsilon)`: pick the best candidate (like Current Best).
With probability `epsilon`: pick a random candidate from the pool.

Default `epsilon = 0.1` means:
- 90% of iterations: mutate the best candidate
- 10% of iterations: mutate a random candidate (explore)

### Using the same example (epsilon = 0.2)

| Candidate | Selection probability |
|---|---|
| Candidate 1 (best) | 80% + (20% × 1/4) = **85%** |
| Candidate 0 | 20% × 1/4 = **5%** |
| Candidate 2 | 20% × 1/4 = **5%** |
| Candidate 3 | 20% × 1/4 = **5%** |

### Pros
- Mostly exploits the best candidate
- Occasionally explores alternatives to escape local optima
- `epsilon` parameter lets you tune the explore/exploit tradeoff

### Cons
- Random exploration picks uniformly — doesn't target promising alternatives
- Requires tuning `epsilon`

---

## Why Pareto is GEPA's default

GEPA was designed as a **general-purpose prompt optimization library** for academic
and research settings. Pareto selection is the default because it solves the
general case well:

1. **Multi-objective optimization** — Research benchmarks often evaluate prompts
   across multiple metrics (accuracy, fluency, safety, etc.). Pareto fronts
   naturally maintain diverse candidates that trade off between objectives.

2. **Large, heterogeneous datasets** — With 100+ evaluation examples spanning
   different categories, no single prompt is best everywhere. Pareto encourages
   maintaining specialists that can later be merged.

3. **Exploration-heavy research** — Researchers often want to discover the full
   landscape of possible prompts, not just converge on one. Pareto selection
   ensures the algorithm keeps exploring different directions.

4. **Robustness over speed** — In a research context, running extra iterations
   is cheap. Pareto avoids premature convergence at the cost of slower progress.

### How our use case differs

Our optimization studio is **not a research tool**. It's a product feature where:

- **Single metric** — We optimize one aggregate score (`pass_rate`), not multiple
  competing objectives. The Pareto front collapses to "who has the highest score
  on each individual item" which is a poor proxy for overall quality.

- **Small datasets** — Typical evaluation suites have 5-30 items. With so few
  items, a candidate that aces 1 item gets significant Pareto weight even if it
  fails everything else.

- **Users expect fast convergence** — Users are watching the optimization in real
  time. Spending 60% of iterations mutating mediocre candidates is unacceptable.

- **Budget is expensive** — Each evaluation costs real API calls (LLM inference).
  Wasted iterations directly cost money.

---

## Pareto at scale: 1000 items, 3 metrics

With a large dataset and multiple metrics, Pareto selection works much better.
Here's why:

### Setup

- **1000 validation items** (e.g. diverse customer support scenarios)
- **3 metrics**: `accuracy`, `empathy`, `safety`
- Pareto front is built per `(item, metric)` pair = **3000 front positions**

### How selection changes

With 3000 front positions instead of 5, the statistics improve dramatically:

| Pool size | Front positions per candidate (avg) | Best candidate probability |
|---|---|---|
| 5 items, 1 metric | 1-2 out of 5 | ~20-40% |
| 1000 items, 3 metrics | ~300 out of 3000 | ~10% ... but meaningful |

The key difference: with 3000 positions, a **niche** candidate that aces 1 item
holds 3/3000 = 0.1% weight. It almost never gets selected. Only candidates that
are genuinely strong across hundreds of items accumulate enough front positions
to get meaningful selection probability.

### Example with 4 candidates

| Candidate | accuracy (avg) | empathy (avg) | safety (avg) | Front positions |
|---|---|---|---|---|
| C0 (seed) | 0.60 | 0.50 | 0.90 | ~200 (safety specialist) |
| C1 | **0.85** | 0.70 | 0.75 | ~800 (accuracy leader) |
| C2 | 0.70 | **0.85** | 0.80 | ~900 (empathy leader) |
| C3 | 0.80 | 0.80 | **0.95** | ~1100 (well-rounded + safety) |

Selection probabilities: C3=37%, C2=30%, C1=27%, C0=7%

This is actually **reasonable** — it naturally balances between candidates that
lead on different metrics. The seed (C0) gets minimal weight because it's only
competitive on safety. The well-rounded C3 gets the most weight because it's
competitive across all three metrics.

### Why it works at scale but not for us

| Factor | 1000 items / 3 metrics | 5 items / 1 metric |
|---|---|---|
| Front positions | 3000 | 5 |
| Niche candidate weight | 0.1% (negligible) | 20% (massive) |
| Metric trade-offs | Real (accuracy vs empathy) | None (single score) |
| Selection quality | Naturally favors strong candidates | Randomly promotes weak ones |
| Exploration value | High (many directions worth exploring) | Low (single direction) |

**Bottom line**: Pareto selection is designed for the multi-objective, large-dataset
case where maintaining diversity is genuinely valuable. In our single-metric,
small-dataset product context, it's the wrong tool.

---

## Recommendation for our use case

For **single-metric optimization on small datasets** (5-30 items),
`"current_best"` is the right default:

- Always improves from the strongest starting point
- No wasted iterations on mediocre parents
- Fastest convergence path to the optimal solution

If we observe local optima issues in practice, we can switch to
`"epsilon_greedy"` with a low epsilon (0.1-0.2) to add occasional exploration.

For comparison:

| Strategy | Convergence speed | Local optima risk | Wasted iterations |
|---|---|---|---|
| `"pareto"` | Slow | Low | High (mediocre parents) |
| `"current_best"` | Fast | High | None |
| `"epsilon_greedy"` | Fast | Medium | Low (~10%) |

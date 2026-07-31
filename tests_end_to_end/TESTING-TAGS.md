# Testing tags

How tests in `tests_end_to_end/` declare what they are and what they cover.

Tags are the coverage map. Nobody maintains a spreadsheet of "which tests cover
prompts" — the specs are the map, and the coverage builder reads these tags.
That only works if the grammar holds, so CI enforces it.

**Scope:** `tests_end_to_end/e2e` (Playwright functional) and
`tests_end_to_end/visual-tests` (Playwright visual). Nothing else.

---

## The four kinds of tag

Tags live **only** in Playwright's `tag` option — never in the test title.

```ts
test.describe('Prompt Library — smoke', {
  tag: ['@t1-smoke', '@area:prompts', '@cap:prompts.list-prompts'],
}, () => { /* ... */ });
```

| Kind | Cardinality | Example | Answers |
|---|---|---|---|
| **tier** | exactly 1 | `@t1-smoke` | how deep / how often does this run? |
| **suite** | 0 or more | `@t1-stsaas` | *where* does this run, outside the ladder? |
| **area** | exactly 1 | `@area:prompts` | which product area is this? |
| **cap** | 1 or more | `@cap:prompts.list-prompts` | which capabilities does it cover? |

### tier — depth

| Tag | Runs | Cost |
|---|---|---|
| `@t1-smoke` | post-merge + prod 3×/day | must be cheap and stable |
| `@t2-cuj` | nightly (as part of t3) | real user journeys |
| `@t3-nightly` | nightly on staging | slowest, most thorough |

Tiers are **cumulative**: `test:t2` runs t1+t2, `test:t3` runs t1+t2+t3.
So pick the tier by *how often it should run*, not by how important it is.

### suite — where, not how deep

**Suites are orthogonal to tier.** This is the part people get wrong.

A suite tag says "also include me in this run", independent of depth. A spec can
be `@t2-cuj` *and* `@t1-stsaas`: t2 depth, but also part of the STSaaS sanity set.

| Tag | Meaning |
|---|---|
| `@t1-stsaas` | include in the STSaaS customer-env sanity run (`test:t1-stsaas`) |
| `@provider-sanity` | LLM-provider matrix; own cadence, no deploy gating |
| `@release-gate` | release-gate spec (see `e2e/tests/_release-gate/README.md`) |
| `@release-gate:<version>` | version-stamped gate spec |

Worked example — `optimization-studio.spec.ts`:

```ts
{ tag: ['@t2-cuj', '@t1-stsaas', '@area:optimization-studio', '@cap:...'] }
```

t2 depth because it's a full user journey. `@t1-stsaas` because Optimization
Studio was historically unstable on customer envs and must be verified there.
**Not** `@t1-smoke`, because every run spends real LLM budget and t1 runs 3×/day.

A spec carrying only a suite tag and no tier is valid — that's a deliberate
opt-out of the ladder (`playground-providers.spec.ts` does this).

### area and cap — coverage

`@area:` must match the spec's directory name. `@cap:` must be
`<area>.<capability>` and both halves must exist in `coverage/taxonomy.yaml`.

```ts
// tests/datasets/dataset-items.spec.ts
{ tag: ['@t2-cuj', '@area:datasets', '@cap:datasets.edit-item-versions'] }
```

Add a `@cap:` for **every** capability the spec actually asserts, not just the
headline one — and only for what it really asserts. A capability counts as covered
when a test carrying its tag **exists**; test health (green/flaky/red) is tracked
and reported separately, so a broken nightly never makes coverage appear to drop.
That makes the tag the claim: an unlisted assertion is invisible coverage, and a
listed one you don't actually assert is a permanent false green.

### Visual specs

Visual specs carry `@vcap:` and **no tier** — they run as one suite.

```ts
// visual-tests/tests/empty-states.spec.ts
{ tag: ['@vcap:datasets.datasets-empty'] }
```

Visual capabilities are *page/state*-shaped, not behaviour-shaped: one screenshot
asserts a whole page renders. They live in the `visual:` block of each area and
each declares a `state:` from `default | empty | loading | error`.

---

## Where tags go: describe vs test

Put shared tags on the `describe`, per-test tags on the `test`. The builder unions
them, so a test inherits everything from its enclosing describe.

```ts
test.describe('Dataset items', { tag: ['@area:datasets'] }, () => {
  test('editing commits a version', {
    tag: ['@t2-cuj', '@cap:datasets.edit-item-versions'],
  }, async () => { /* ... */ });

  test('bulk delete commits a version', {
    tag: ['@t3-nightly', '@cap:datasets.bulk-delete-items'],
  }, async () => { /* ... */ });
});
```

A file may hold several describes at **different tiers** — `ollie-agentic.spec.ts`
has three. That's fine and often right.

---

## Naming

**Tags:** kebab-case, lowercase. `@cap:` is `<area>.<capability>`, both kebab.

**Test titles:** `<capability>: <behavior>` — state the behaviour, not the mechanic.

```ts
// good — says what must be true
test('Editing an item field commits as a new version and round-trips to the SDK')

// bad — describes clicking, not the guarantee
test('click edit then save then check')
```

**Directory = area.** `tests/<area>/` and `@area:<area>` must match. Enforced.

---

## test.step()

Wrap each phase in `test.step()`. On failure Allure names the failing step, which
turns "the test broke" into "seeding worked, the assertion on version 2 failed".

```ts
const dataset = await test.step('Seed a dataset via the SDK', async () => { /* ... */ });
await test.step('Edit an item field and commit', async () => { /* ... */ });
await test.step('Verify the edit round-trips to the SDK', async () => { /* ... */ });
```

Already the house style: 22 of 24 functional specs and 16 of 21 page objects use
it. Steps may return values, and step titles may be template literals.

---

## Allure

Playwright tags reach Allure automatically — **no `allure.label()` calls needed.**

Allure strips the leading `@`, so `@cap:prompts.list-prompts` is queryable as:

```
tag = "cap:prompts.list-prompts"
```

Compound queries work, which is what the coverage builder uses:

```
tag = "area:traces" and status = "passed"
```

Everything reports to project **1** at `https://comet.testops.cloud/` — both Opik
and EM, so segment by launch name or tag, never by project id.

---

## Adding an area or capability

The taxonomy is a **reviewed** file: it defines 100%, so adding to it changes the
denominator and lowers coverage until tests exist. That's intended — it makes a
known gap visible instead of silently absent.

1. Add the area or capability to `coverage/taxonomy.yaml` (with `covered: false`).
2. Get it reviewed — this is a QA-owned decision, not an implementation detail.
3. Tag specs with the new `@cap:` as coverage lands, flipping `covered: true`.

Capability altitude: aim for **5–15 per area**, each a user-visible behaviour a
test could plausibly assert. "Create a prompt" is a capability. "Click the save
button" is not. Tabs usually *are* separate capabilities.

Renaming an area: add the old name to `tag_aliases:` so historical Allure results
still resolve. If the old tag no longer maps to exactly one area, add it to
`retired_tags:` instead and resolve per spec — see `trace-explore`, which split
into `traces` and `threads`.

---

## CI enforcement

`.github/workflows/tag_lint.yml` runs on every PR touching `tests_end_to_end/`.
Hard failure, not a warning — an untagged spec is invisible to `--grep`, so it
silently never runs and `npm test` even passes `--pass-with-no-tests`. That's the
failure mode this prevents.

Run it locally the same way CI does, from the repo root:

```bash
pip install pyyaml
python3 tests_end_to_end/coverage/tag_lint.py \
  --taxonomy tests_end_to_end/coverage/taxonomy.yaml \
  --estate tests_end_to_end
```

Findings are line-anchored, so in CI (`--format github`) they appear on the
offending line in the PR's Files-changed view.

Enforced:

1. every non-exempt e2e spec has a tier (or a suite opt-out) and exactly one `@area:`
2. every non-visual spec declares at least one `@cap:`
3. `@area:` / `@cap:` / `@vcap:` all resolve in the taxonomy
4. `@cap:` sits under the spec's declared area
5. visual specs carry `@vcap:` and no tier
6. every visual capability in `taxonomy.yaml` has a `state:` from the enum
7. no unrecognised tags

**Not** enforced: tier *cardinality*. "Exactly one tier" is a rule about a single
test after describe-inheritance, and the linter reads string literals, not the TS
AST — it cannot tell a file whose sibling describes differ (legitimate, and four
specs do it) from one test carrying two tiers (wrong). Keep it right by hand.

A computed tag is invisible. `tag: [variant.cap]` passes the lint and silently
contributes nothing to coverage, because both the lint and the builder match
quoted literals inside `tag: [...]`. If you find yourself generating tests in a
loop where each iteration covers a *different* capability, write them as separate
`test()` calls with literal tags — see `prompt-library-smoke.spec.ts`.

Exempt (`rules.exempt_dirs`): `_seed` (harness self-test), `_release-gate`
(ephemeral, version-stamped).

Legacy and retired tags get a specific message telling you the replacement,
rather than a generic "unrecognised".

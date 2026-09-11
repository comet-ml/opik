# Getting Runnable SQL Out of a DAO

DAO queries in `apps/opik-backend/src/main/java/com/comet/opik/domain/` are Java text blocks carrying
StringTemplate directives (`<if(x)>…<endif>`, `<filters>`) and r2dbc named parameters
(`:workspace_id`), so they cannot be run as written. Render them outside Java — resolving the
directives for one call site and substituting the parameters — and produce:

- **one rendering per call site**, because a single constant serving a list page and a by-id lookup
  binds different parameters and can be improved for one while regressing the other. Take the
  bindings from the Java (`bind(` / `ifPresent(… bind …)` around `TemplateUtils.newST(…)`), not from a
  guess;
- **the same constant rendered from `main`**, so the baseline is the current production query.

Two failure modes to rule out before trusting a rendering: a surviving `:param` (it silently becomes a
different query, so fail loudly instead), and a call site rendered with the wrong parameter set. Diff
renderings rather than sources when checking a refactor — a comment-only or formatting-only edit must
produce an identical query once comment lines are stripped.

**Repo hazard**: a line inside a query containing only `--` makes `ClickHouseParameterizedQuery` stop
substituting later `:params`, so the real endpoint fails with `Code: 62 Syntax error … :workspace_id`
even though your rendering looks fine.

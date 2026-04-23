# OPIK-6040 — Deployment-level default rows-per-page (v2 Experiments tables)

**Status:** Approved
**Ticket:** [OPIK-6040](https://comet-ml.atlassian.net/browse/OPIK-6040)
**Branch:** `awkoy/opik-6040-rows-per-page-default`
**Date:** 2026-04-23

## Problem

Opik's table-based views hardcode a default page size of 100 rows. Netflix (high trace volume per experiment) wants admins to lower that default globally via a deployment config, without asking every user to change it per session and without relying on client-side persistence. The customer-visible symptom is sluggish rendering on the Experiments pages when 100 trace-heavy rows are loaded at once.

## Goals

1. Opik admin can set a global default rows-per-page value at deployment time (env var / Helm value).
2. Default applies on fresh page load for all users without requiring localStorage to be empty.
3. User can still override the page size in-session via the existing rows-per-page dropdown; the override is not persisted beyond the URL.

## Non-goals

- v1 pages. Only v2 is in scope.
- Non-Experiments tables (Traces, Spans, Threads, Datasets, etc.). The mechanism must be reusable for them later, but this change only wires up the three v2 Experiments tables.
- Per-workspace or per-user server-side defaults.
- UI affordance that shows the configured default to end users ("admin set 25").
- Cleanup of stale `experiments-pagination-size` localStorage entries in users' browsers.

## Scope — tables touched

Three v2 tables, all currently hardcoding `defaultValue: 100` in a `useQueryParamAndLocalStorageState` call:

1. `apps/opik-frontend/src/v2/pages/ExperimentsPage/GeneralDatasetsTab/GeneralDatasetsTab.tsx:172` — main Experiments list.
2. `apps/opik-frontend/src/v2/pages-shared/experiments/useExperimentItemsState.ts:32` — experiment items viewer (dataset items per experiment).
3. `apps/opik-frontend/src/v2/pages/PromptPage/ExperimentsTab/ExperimentsTab.tsx:134` — prompt page's experiments tab.

## Architecture

```
Helm values.yaml: UI_DEFAULT_PAGE_SIZE=25
   └─> opik-backend config.yml: ${UI_DEFAULT_PAGE_SIZE:-100}
       └─> UIConfig.defaultPageSize (Dropwizard, @Min(1))
           └─> GET /v1/private/ui-config/  (new UIConfigResource)
               └─> useUIConfig() React Query hook
                   └─> <UIConfigProvider> (mirrors feature-toggles-provider)
                       └─> v2 Experiments tables
                           → useQueryParam("size", NumberParam) with default = context value
```

## Backend

### New files

- `apps/opik-backend/src/main/java/com/comet/opik/infrastructure/UIConfig.java`
  - Dropwizard config POJO.
  - Single field: `@JsonProperty @Min(1) private int defaultPageSize = 100;`.
  - Standard Lombok `@Data`.

- `apps/opik-backend/src/main/java/com/comet/opik/api/UIConfig.java`
  - Public API DTO returned by the resource. Same shape as the config class.
  - Separate class from the infrastructure config to keep the API-surface decoupled from the Dropwizard binding (matches existing pattern).

- `apps/opik-backend/src/main/java/com/comet/opik/api/resources/v1/priv/UIConfigResource.java`
  - `@Path("/v1/private/ui-config/")`, `@GET`, `@Produces(APPLICATION_JSON)`.
  - Injected with `UIConfig` (infrastructure). Returns the API DTO.
  - Auth handled the same way `ServiceTogglesResource` handles it.

### Modified files

- `apps/opik-backend/src/main/java/com/comet/opik/infrastructure/OpikConfiguration.java`
  - Add `@Valid @NotNull @JsonProperty private UIConfig uiConfig = new UIConfig();`.

- `apps/opik-backend/config.yml`
  - Add top-level block:
    ```yaml
    uiConfig:
      defaultPageSize: ${UI_DEFAULT_PAGE_SIZE:-100}
    ```

- Resource registration site (same place `ServiceTogglesResource` is registered — Guice module or `OpikApplication.java`).

- `deployment/helm_chart/opik/values.yaml`
  - Add `UI_DEFAULT_PAGE_SIZE: "100"` to `components.backend.env` so operators have a visible hook to change.

### Validation

- `@Min(1)` on `defaultPageSize`. Dropwizard fails fast on startup if an admin sets `0` or a negative value.
- Non-integer env var value (e.g. `"abc"`) fails YAML/Jackson parsing at startup — operator sees a clear error.

## Frontend

### New files

- `apps/opik-frontend/src/types/ui-config.ts`
  ```ts
  export type UIConfig = { defaultPageSize: number };
  ```

- `apps/opik-frontend/src/api/ui-config/useUIConfig.ts`
  - React Query wrapper. `queryKey: ["ui-config"]`. Cached globally (value is deployment-wide, same across workspaces). StaleTime follows the convention used by `useFeatureToggle`.
  - Calls `UI_CONFIG_REST_ENDPOINT` via the shared API client.

- `apps/opik-frontend/src/contexts/ui-config-provider.tsx`
  - `<UIConfigProvider>` calls `useUIConfig()` at mount and exposes `{ defaultPageSize }` via context.
  - Exports `useUIConfigValue(): UIConfig` hook.
  - Fallback on error or loading: `{ defaultPageSize: 100 }` so consumers always have a valid number.

### Modified files

- `apps/opik-frontend/src/api/api.ts`
  - Add `UI_CONFIG_REST_ENDPOINT = "/v1/private/ui-config/"`.

- v2 app root (same wrapping location as `FeatureTogglesProvider`)
  - Wrap children with `<UIConfigProvider>`.

- `apps/opik-frontend/src/v2/pages/ExperimentsPage/GeneralDatasetsTab/GeneralDatasetsTab.tsx` (line 172 area)
  - Replace `useQueryParamAndLocalStorageState` for page size with `useQueryParam("size", NumberParam)`.
  - Use `useUIConfigValue().defaultPageSize` as the effective default when the URL has no `size`.
  - Remove the now-unused `PAGINATION_SIZE_KEY` constant (line 114).

- `apps/opik-frontend/src/v2/pages-shared/experiments/useExperimentItemsState.ts` (line 32 area)
  - Same treatment.

- `apps/opik-frontend/src/v2/pages/PromptPage/ExperimentsTab/ExperimentsTab.tsx` (line 134 area)
  - Same treatment.

## Data flow & override semantics

- **Fresh visit, no `?size=` in URL**: table renders with `uiConfig.defaultPageSize`.
- **User picks a size from dropdown**: URL gains `?size=50`. In-session override sticks as long as URL keeps it (refresh → still 50; browser back/forward respects URL state). No localStorage write.
- **User navigates away and returns without the query param**: deployment default applies again.
- **Existing users with stale `experiments-pagination-size` localStorage values**: ignored — the key is no longer read. Matches the ticket's "without relying on client-side persistence."

## Error handling

- Backend startup with invalid `UI_DEFAULT_PAGE_SIZE`: Dropwizard validation / Jackson parsing fails fast. Operator sees a clear error in logs; deploy cannot proceed.
- Frontend fetch fails (network error, 4xx/5xx): `useUIConfigValue()` returns fallback `{ defaultPageSize: 100 }`. Tables render normally — no visible regression.
- Backend returns unexpected shape: same fallback applies via React Query's error path.

## Testing

### Backend

- `UIConfigResourceTest` (integration): set `UI_DEFAULT_PAGE_SIZE=25` in the test config, assert `GET /v1/private/ui-config/` returns `{"defaultPageSize": 25}`. Second case: env var absent → 100.
- Validation test: Dropwizard config with `defaultPageSize: 0` or `-1` fails validation on app boot.

### Frontend

- `ui-config-provider.test.tsx`: provider exposes fetched value on success; exposes `{ defaultPageSize: 100 }` on fetch error.
- One integration test on `GeneralDatasetsTab` (representative of the three consumers): mock `useUIConfigValue` to return `25`, render with no URL `size` param, assert the pagination shows size 25. The other two consumers use the exact same hook call; one integration test plus unit coverage on the provider is sufficient.

## Risks & mitigations

- **Risk:** Existing user with localStorage-persisted large page size suddenly sees the smaller deployment default. Could be surprising.
  - **Mitigation:** Matches the ticket's explicit requirement ("without relying on client-side persistence"). User can re-pick via the dropdown; override sticks for the session.
- **Risk:** Frontend fetch of `/v1/private/ui-config/` fails on first workspace load and the user sees 100 temporarily. On next load with a successful fetch, they see the configured default — may feel inconsistent.
  - **Mitigation:** Fetch is lightweight and cached workspace-wide; React Query retries on network failure. In practice, fetch reliability matches `feature-toggles` which is already in production.
- **Risk:** Admin sets a value not in the dropdown option list `[5, 10, 25, 50, 100]`, e.g. 30. The default size works but the dropdown won't show the current selection as a discrete choice.
  - **Mitigation:** Acceptable for this ticket. The user can pick any of the standard options; the underlying default remains 30 until they override. If later we want "admin default shown in dropdown" UX, that's a separate feature.

## Out of scope (reiterated)

- v1 pages.
- Any table outside the three v2 Experiments tables. Mechanism (`UIConfigProvider`) is designed to be reusable — future tickets can adopt it with a one-line hook call.
- Per-user or per-workspace server-side defaults.
- UI indication of the admin-set default.

## Open questions

None. All design decisions confirmed with the user.

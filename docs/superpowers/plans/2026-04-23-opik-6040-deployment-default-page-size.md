# OPIK-6040 Deployment-level Default Rows-per-Page — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose a deployment-level default rows-per-page value (env var / Helm value) to the three v2 Experiments tables so Opik admins can lower the default from 100 without relying on client-side localStorage persistence.

**Architecture:** A new Dropwizard config `UIConfig` is wired into `OpikConfiguration` and served at `GET /v1/private/ui-config/`. The frontend fetches it via a new React Query hook and exposes the value through a `UIConfigProvider` context that mirrors `FeatureTogglesProvider`. The three v2 Experiments tables switch from `useQueryParamAndLocalStorageState` to `useQueryParam`, reading the default from the provider — no localStorage write for page size, URL `?size=` provides in-session override.

**Tech Stack:** Java 21 + Dropwizard + Guicey (backend), React + TypeScript + React Query + use-query-params (frontend), Helm (deployment).

**Spec:** `docs/superpowers/specs/2026-04-23-opik-6040-deployment-default-page-size-design.md`

---

## File Structure

### Backend — Create

- `apps/opik-backend/src/main/java/com/comet/opik/infrastructure/UIConfig.java` — Dropwizard config POJO.
- `apps/opik-backend/src/main/java/com/comet/opik/api/resources/v1/priv/UIConfigResource.java` — JAX-RS resource serving `GET /v1/private/ui-config/`.
- `apps/opik-backend/src/test/java/com/comet/opik/api/resources/v1/priv/UIConfigResourceTest.java` — unit test for the resource.

### Backend — Modify

- `apps/opik-backend/src/main/java/com/comet/opik/infrastructure/OpikConfiguration.java` — add `uiConfig` field.
- `apps/opik-backend/config.yml` — add `uiConfig:` block with env var substitution.
- `apps/opik-backend/src/test/resources/config-test.yml` — add `uiConfig:` block for test runs.

### Frontend — Create

- `apps/opik-frontend/src/types/ui-config.ts` — TS types.
- `apps/opik-frontend/src/api/ui-config/useUIConfig.ts` — React Query hook.
- `apps/opik-frontend/src/contexts/ui-config-provider.tsx` — context + provider + consumer hook.
- `apps/opik-frontend/src/contexts/ui-config-provider.test.tsx` — unit test for fallback behavior.

### Frontend — Modify

- `apps/opik-frontend/src/api/api.ts` — add `UI_CONFIG_REST_ENDPOINT` constant.
- `apps/opik-frontend/src/v2/layout/WorkspaceGuard/WorkspaceGuard.tsx` — wrap layout with `UIConfigProvider`.
- `apps/opik-frontend/src/v2/pages/ExperimentsPage/GeneralDatasetsTab/GeneralDatasetsTab.tsx` — replace page-size state hook.
- `apps/opik-frontend/src/v2/pages-shared/experiments/useExperimentItemsState.ts` — replace page-size state hook.
- `apps/opik-frontend/src/v2/pages/PromptPage/ExperimentsTab/ExperimentsTab.tsx` — replace page-size state hook.

### Deployment — Modify

- `deployment/helm_chart/opik/values.yaml` — add `UI_DEFAULT_PAGE_SIZE` under `components.backend.env`.

---

## Task 1: Add UIConfig Dropwizard config class

**Files:**
- Create: `apps/opik-backend/src/main/java/com/comet/opik/infrastructure/UIConfig.java`
- Modify: `apps/opik-backend/src/main/java/com/comet/opik/infrastructure/OpikConfiguration.java`
- Modify: `apps/opik-backend/src/test/resources/config-test.yml`

- [ ] **Step 1: Create the config POJO**

Create `apps/opik-backend/src/main/java/com/comet/opik/infrastructure/UIConfig.java`:

```java
package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class UIConfig {

    @JsonProperty
    @Min(1)
    private int defaultPageSize = 100;
}
```

- [ ] **Step 2: Wire the config into OpikConfiguration**

Edit `apps/opik-backend/src/main/java/com/comet/opik/infrastructure/OpikConfiguration.java`. After the `serviceToggles` field (around line 87), add a new field:

```java
    @Valid @NotNull @JsonProperty
    private UIConfig uiConfig = new UIConfig();
```

- [ ] **Step 3: Add the test config block so existing tests still boot**

Edit `apps/opik-backend/src/test/resources/config-test.yml`. After the `serviceToggles:` block (starts around line 496), append at the same indentation level (top-level):

```yaml
uiConfig:
  # Default: 100
  # Description: Deployment-level default rows-per-page for UI tables
  defaultPageSize: 100
```

- [ ] **Step 4: Verify the backend still compiles and tests start up**

Run: `cd apps/opik-backend && mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add apps/opik-backend/src/main/java/com/comet/opik/infrastructure/UIConfig.java apps/opik-backend/src/main/java/com/comet/opik/infrastructure/OpikConfiguration.java apps/opik-backend/src/test/resources/config-test.yml
git commit -m "[OPIK-6040] feat(backend): add UIConfig with defaultPageSize

Introduce a Dropwizard UIConfig class that holds deployment-level UI
defaults. Field defaultPageSize (validated @Min(1), default 100) lets
admins lower the global default rows-per-page for table views.
"
```

---

## Task 2: Add UIConfigResource endpoint

**Files:**
- Create: `apps/opik-backend/src/main/java/com/comet/opik/api/resources/v1/priv/UIConfigResource.java`

- [ ] **Step 1: Create the resource class**

Create `apps/opik-backend/src/main/java/com/comet/opik/api/resources/v1/priv/UIConfigResource.java`:

```java
package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.UIConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Path("/v1/private/ui-config/")
@Produces(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "UI Config", description = "Deployment-level UI configuration")
public class UIConfigResource {

    private final @NonNull OpikConfiguration config;

    @GET
    @Operation(operationId = "getUIConfig", summary = "Get UI Config", description = "Get deployment-level UI configuration values consumed by the frontend", responses = {
            @ApiResponse(responseCode = "200", description = "UI Config", content = @Content(schema = @Schema(implementation = UIConfig.class)))})
    public Response getUIConfig() {
        return Response.ok()
                .entity(config.getUiConfig())
                .build();
    }
}
```

- [ ] **Step 2: Verify the backend still compiles**

Run: `cd apps/opik-backend && mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add apps/opik-backend/src/main/java/com/comet/opik/api/resources/v1/priv/UIConfigResource.java
git commit -m "[OPIK-6040] feat(backend): expose UIConfig via /v1/private/ui-config

Add UIConfigResource mirroring ServiceTogglesResource. The endpoint is
auto-discovered by Guicey (enableAutoConfig) and serves the uiConfig
block from OpikConfiguration.
"
```

---

## Task 3: Unit test UIConfigResource

**Files:**
- Create: `apps/opik-backend/src/test/java/com/comet/opik/api/resources/v1/priv/UIConfigResourceTest.java`

- [ ] **Step 1: Write a failing unit test for the resource**

Create `apps/opik-backend/src/test/java/com/comet/opik/api/resources/v1/priv/UIConfigResourceTest.java`:

```java
package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.UIConfig;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UIConfigResource")
class UIConfigResourceTest {

    @Test
    @DisplayName("returns the configured defaultPageSize value")
    void returnsConfiguredDefaultPageSize() {
        UIConfig uiConfig = new UIConfig();
        uiConfig.setDefaultPageSize(25);

        OpikConfiguration config = new OpikConfiguration();
        config.setUiConfig(uiConfig);

        UIConfigResource resource = new UIConfigResource(config);

        Response response = resource.getUIConfig();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity()).isEqualTo(uiConfig);
        assertThat(((UIConfig) response.getEntity()).getDefaultPageSize()).isEqualTo(25);
    }

    @Test
    @DisplayName("defaults to 100 when no override is provided")
    void returnsDefaultWhenNotOverridden() {
        OpikConfiguration config = new OpikConfiguration();
        UIConfigResource resource = new UIConfigResource(config);

        Response response = resource.getUIConfig();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(((UIConfig) response.getEntity()).getDefaultPageSize()).isEqualTo(100);
    }
}
```

- [ ] **Step 2: Run the test to see it pass**

Run: `cd apps/opik-backend && mvn -q test -Dtest=UIConfigResourceTest`
Expected: Tests run: 2, Failures: 0, Errors: 0.

- [ ] **Step 3: Commit**

```bash
git add apps/opik-backend/src/test/java/com/comet/opik/api/resources/v1/priv/UIConfigResourceTest.java
git commit -m "[OPIK-6040] test(backend): unit test UIConfigResource returns config value

Cover the custom-value and default-value paths. Kept as a unit test to
avoid booting ClickHouse/MySQL/Redis for a read-only config endpoint.
"
```

---

## Task 4: Wire env var in backend config.yml

**Files:**
- Modify: `apps/opik-backend/config.yml`

- [ ] **Step 1: Add the uiConfig block to the production config**

Edit `apps/opik-backend/config.yml`. After the `serviceToggles:` block (ends around line ~990 — find it by searching for `serviceToggles:` at the top level), add a new top-level block. Insert at the end of the file, matching the top-level indentation:

```yaml
uiConfig:
  # Default: 100
  # Description: Deployment-level default rows-per-page for UI tables.
  #              Lower this value to reduce initial render cost for
  #              high-volume workspaces.
  defaultPageSize: ${UI_DEFAULT_PAGE_SIZE:-100}
```

- [ ] **Step 2: Verify config parses cleanly**

Run: `cd apps/opik-backend && mvn -q test -Dtest=UIConfigResourceTest`
Expected: still passes (no regression).

- [ ] **Step 3: Commit**

```bash
git add apps/opik-backend/config.yml
git commit -m "[OPIK-6040] feat(backend): wire UI_DEFAULT_PAGE_SIZE env var

Add uiConfig block to config.yml with env var substitution so operators
can override the default rows-per-page at deploy time without a rebuild.
"
```

---

## Task 5: Frontend API plumbing — types, endpoint, hook

**Files:**
- Create: `apps/opik-frontend/src/types/ui-config.ts`
- Create: `apps/opik-frontend/src/api/ui-config/useUIConfig.ts`
- Modify: `apps/opik-frontend/src/api/api.ts`

- [ ] **Step 1: Add the API endpoint constant**

Edit `apps/opik-frontend/src/api/api.ts`. After line 14 (the `FEATURE_TOGGLES_REST_ENDPOINT` declaration), add:

```ts
export const UI_CONFIG_REST_ENDPOINT = "/v1/private/ui-config/";
```

- [ ] **Step 2: Add the TS type**

Create `apps/opik-frontend/src/types/ui-config.ts`:

```ts
export type UIConfig = {
  default_page_size: number;
};
```

Note: snake_case on the wire because the backend's global `ObjectMapper` uses `SnakeCaseStrategy` (see `OpikApplication.java:120`). Every other API type in `apps/opik-frontend/src/types/` uses snake_case property names for the same reason (e.g. `created_at`, `last_updated_at`).

- [ ] **Step 3: Add the React Query hook**

Create `apps/opik-frontend/src/api/ui-config/useUIConfig.ts`:

```ts
import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, UI_CONFIG_REST_ENDPOINT } from "@/api/api";
import { UIConfig } from "@/types/ui-config";

const getUIConfig = async ({ signal }: QueryFunctionContext) => {
  const { data } = await api.get<UIConfig>(UI_CONFIG_REST_ENDPOINT, {
    signal,
  });

  return data;
};

export default function useUIConfig(options?: QueryConfig<UIConfig>) {
  return useQuery({
    queryKey: ["ui-config"],
    queryFn: (context) => getUIConfig(context),
    ...options,
  });
}
```

- [ ] **Step 4: Verify the frontend type-checks**

Run: `cd apps/opik-frontend && npm run typecheck`
Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add apps/opik-frontend/src/api/api.ts apps/opik-frontend/src/types/ui-config.ts apps/opik-frontend/src/api/ui-config/useUIConfig.ts
git commit -m "[OPIK-6040] feat(frontend): add UIConfig type, endpoint, and fetch hook

Cache key is global (\"ui-config\") because the value is deployment-wide
and does not vary per workspace.
"
```

---

## Task 6: Frontend UIConfigProvider context

**Files:**
- Create: `apps/opik-frontend/src/contexts/ui-config-provider.tsx`

- [ ] **Step 1: Create the provider**

Create `apps/opik-frontend/src/contexts/ui-config-provider.tsx`:

```tsx
import { createContext, useContext, useMemo } from "react";
import useUIConfig from "@/api/ui-config/useUIConfig";
import { UIConfig } from "@/types/ui-config";

const DEFAULT_UI_CONFIG: UIConfig = {
  default_page_size: 100,
};

const UIConfigContext = createContext<UIConfig>(DEFAULT_UI_CONFIG);

type UIConfigProviderProps = {
  children: React.ReactNode;
};

export function UIConfigProvider({ children }: UIConfigProviderProps) {
  const { data } = useUIConfig();

  const value = useMemo<UIConfig>(
    () => data ?? DEFAULT_UI_CONFIG,
    [data],
  );

  return (
    <UIConfigContext.Provider value={value}>
      {children}
    </UIConfigContext.Provider>
  );
}

export const useUIConfigValue = (): UIConfig => useContext(UIConfigContext);
```

- [ ] **Step 2: Verify the frontend type-checks**

Run: `cd apps/opik-frontend && npm run typecheck`
Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add apps/opik-frontend/src/contexts/ui-config-provider.tsx
git commit -m "[OPIK-6040] feat(frontend): add UIConfigProvider context

Exposes deployment UI config to the component tree with a 100-default
fallback so consumers always have a valid number during fetch / on
error.
"
```

---

## Task 7: Unit test the UIConfigProvider fallback behavior

**Files:**
- Create: `apps/opik-frontend/src/contexts/ui-config-provider.test.tsx`

- [ ] **Step 1: Write the failing test**

Create `apps/opik-frontend/src/contexts/ui-config-provider.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ReactNode } from "react";
import {
  UIConfigProvider,
  useUIConfigValue,
} from "./ui-config-provider";

const mockUseUIConfig = vi.fn();

vi.mock("@/api/ui-config/useUIConfig", () => ({
  default: () => mockUseUIConfig(),
}));

function Consumer() {
  const { default_page_size } = useUIConfigValue();
  return <span data-testid="size">{default_page_size}</span>;
}

function renderWithProviders(ui: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <UIConfigProvider>{ui}</UIConfigProvider>
    </QueryClientProvider>,
  );
}

describe("UIConfigProvider", () => {
  beforeEach(() => {
    mockUseUIConfig.mockReset();
  });

  it("exposes the fetched default_page_size when the hook returns data", async () => {
    mockUseUIConfig.mockReturnValue({ data: { default_page_size: 25 } });

    renderWithProviders(<Consumer />);

    await waitFor(() => {
      expect(screen.getByTestId("size").textContent).toBe("25");
    });
  });

  it("falls back to 100 when the hook has no data (loading / error)", () => {
    mockUseUIConfig.mockReturnValue({ data: undefined });

    renderWithProviders(<Consumer />);

    expect(screen.getByTestId("size").textContent).toBe("100");
  });
});
```

- [ ] **Step 2: Run the test to verify it passes**

Run: `cd apps/opik-frontend && npx vitest run src/contexts/ui-config-provider.test.tsx`
Expected: 2 passed.

- [ ] **Step 3: Commit**

```bash
git add apps/opik-frontend/src/contexts/ui-config-provider.test.tsx
git commit -m "[OPIK-6040] test(frontend): cover UIConfigProvider success and fallback paths

Verifies the provider exposes fetched data and falls back to
defaultPageSize=100 when the query is loading or errors.
"
```

---

## Task 8: Wire UIConfigProvider into v2 WorkspaceGuard

**Files:**
- Modify: `apps/opik-frontend/src/v2/layout/WorkspaceGuard/WorkspaceGuard.tsx`

- [ ] **Step 1: Import the provider**

Edit `apps/opik-frontend/src/v2/layout/WorkspaceGuard/WorkspaceGuard.tsx`. Add an import next to the existing `FeatureTogglesProvider` import (line 4):

```tsx
import { UIConfigProvider } from "@/contexts/ui-config-provider";
```

- [ ] **Step 2: Wrap the layout with the provider**

In the same file, replace the `layout` JSX (currently lines 33–39):

```tsx
  const layout = (
    <FeatureTogglesProvider>
      <ServerSyncProvider>
        <Layout />
      </ServerSyncProvider>
    </FeatureTogglesProvider>
  );
```

with:

```tsx
  const layout = (
    <FeatureTogglesProvider>
      <UIConfigProvider>
        <ServerSyncProvider>
          <Layout />
        </ServerSyncProvider>
      </UIConfigProvider>
    </FeatureTogglesProvider>
  );
```

- [ ] **Step 3: Verify the frontend type-checks**

Run: `cd apps/opik-frontend && npm run typecheck`
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add apps/opik-frontend/src/v2/layout/WorkspaceGuard/WorkspaceGuard.tsx
git commit -m "[OPIK-6040] feat(frontend): mount UIConfigProvider in v2 WorkspaceGuard

Provider is nested inside FeatureTogglesProvider since the UI config
fetch is independent of feature toggles and should cover the same set of
authenticated v2 pages.
"
```

---

## Task 9: Consume the default in v2 ExperimentsPage GeneralDatasetsTab

**Files:**
- Modify: `apps/opik-frontend/src/v2/pages/ExperimentsPage/GeneralDatasetsTab/GeneralDatasetsTab.tsx`

- [ ] **Step 1: Add the import and drop the stale hook import**

At the top of `GeneralDatasetsTab.tsx`, add:

```tsx
import { useUIConfigValue } from "@/contexts/ui-config-provider";
```

Search for any existing `useQueryParamAndLocalStorageState` import in this file. If it is no longer used elsewhere in the file after this change, remove the import line. (Run a final quick search with `grep -n useQueryParamAndLocalStorageState GeneralDatasetsTab.tsx` after the edit to confirm.)

- [ ] **Step 2: Delete the unused PAGINATION_SIZE_KEY constant**

Remove line 114:

```tsx
const PAGINATION_SIZE_KEY = "experiments-pagination-size";
```

- [ ] **Step 3: Replace the page-size hook call**

Inside `GeneralDatasetsTab`, read the deployment default and swap the hook. Find the block at line ~172:

```tsx
  const [size, setSize] = useQueryParamAndLocalStorageState<
    number | null | undefined
  >({
    localStorageKey: PAGINATION_SIZE_KEY,
    queryKey: "size",
    defaultValue: 100,
    queryParamConfig: NumberParam,
    syncQueryWithLocalStorageOnInit: true,
  });
```

Replace with:

```tsx
  const { default_page_size: defaultPageSize } = useUIConfigValue();
  const [sizeParam, setSize] = useQueryParam("size", NumberParam, {
    updateType: "replaceIn",
  });
  const size = sizeParam ?? defaultPageSize;
```

(The existing `useQueryParam` and `NumberParam` imports on lines 12–16 already cover this.)

- [ ] **Step 4: Verify the frontend type-checks**

Run: `cd apps/opik-frontend && npm run typecheck`
Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add apps/opik-frontend/src/v2/pages/ExperimentsPage/GeneralDatasetsTab/GeneralDatasetsTab.tsx
git commit -m "[OPIK-6040] feat(frontend): use deployment default in v2 experiments list

Replace useQueryParamAndLocalStorageState with useQueryParam for the
page-size state. The deployment default comes from UIConfigProvider and
is no longer persisted to localStorage; in-session override lives only
on the URL query param per customer requirement.
"
```

---

## Task 10: Consume the default in v2 useExperimentItemsState

**Files:**
- Modify: `apps/opik-frontend/src/v2/pages-shared/experiments/useExperimentItemsState.ts`

- [ ] **Step 1: Add the import**

At the top of `useExperimentItemsState.ts`, add:

```ts
import { useUIConfigValue } from "@/contexts/ui-config-provider";
```

- [ ] **Step 2: Replace the page-size hook call**

Find the block at line ~27:

```ts
  const [size, setSize] = useQueryParamAndLocalStorageState<
    number | null | undefined
  >({
    localStorageKey: `${storagePrefix}-pagination-size`,
    queryKey: "size",
    defaultValue: 100,
    queryParamConfig: NumberParam,
    syncQueryWithLocalStorageOnInit: true,
  });
```

Replace with:

```ts
  const { default_page_size: defaultPageSize } = useUIConfigValue();
  const [sizeParam, setSize] = useQueryParam("size", NumberParam, {
    updateType: "replaceIn",
  });
  const size = sizeParam ?? defaultPageSize;
```

Leave the other `useQueryParamAndLocalStorageState` calls (for `height` and `sorting`) untouched — they still need localStorage persistence.

- [ ] **Step 3: Verify the frontend type-checks**

Run: `cd apps/opik-frontend && npm run typecheck`
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add apps/opik-frontend/src/v2/pages-shared/experiments/useExperimentItemsState.ts
git commit -m "[OPIK-6040] feat(frontend): use deployment default in experiment items viewer

Only the page-size state drops localStorage persistence; row height,
sorting, and other items-viewer state remain in localStorage per the
existing UX contract.
"
```

---

## Task 11: Consume the default in v2 PromptPage ExperimentsTab

**Files:**
- Modify: `apps/opik-frontend/src/v2/pages/PromptPage/ExperimentsTab/ExperimentsTab.tsx`

- [ ] **Step 1: Add the import**

At the top of `ExperimentsTab.tsx`, add:

```tsx
import { useUIConfigValue } from "@/contexts/ui-config-provider";
```

- [ ] **Step 2: Delete the unused PAGINATION_SIZE_KEY constant**

Remove line 72:

```tsx
const PAGINATION_SIZE_KEY = "prompt-experiments-pagination-size";
```

- [ ] **Step 3: Replace the page-size hook call**

Find the block at line ~129:

```tsx
  const [size, setSize] = useQueryParamAndLocalStorageState<
    number | null | undefined
  >({
    localStorageKey: PAGINATION_SIZE_KEY,
    queryKey: "size",
    defaultValue: 100,
    queryParamConfig: NumberParam,
    syncQueryWithLocalStorageOnInit: true,
  });
```

Replace with:

```tsx
  const { default_page_size: defaultPageSize } = useUIConfigValue();
  const [sizeParam, setSize] = useQueryParam("size", NumberParam, {
    updateType: "replaceIn",
  });
  const size = sizeParam ?? defaultPageSize;
```

- [ ] **Step 4: Clean up the now-unused import if this was its last usage in the file**

Run `grep -n useQueryParamAndLocalStorageState apps/opik-frontend/src/v2/pages/PromptPage/ExperimentsTab/ExperimentsTab.tsx`. If no matches remain, delete the import line at the top of the file.

- [ ] **Step 5: Verify the frontend type-checks**

Run: `cd apps/opik-frontend && npm run typecheck`
Expected: no errors.

- [ ] **Step 6: Commit**

```bash
git add apps/opik-frontend/src/v2/pages/PromptPage/ExperimentsTab/ExperimentsTab.tsx
git commit -m "[OPIK-6040] feat(frontend): use deployment default in prompt page experiments tab

Completes the v2 Experiments surfaces that now read default rows-per-page
from UIConfigProvider and stop persisting page size to localStorage.
"
```

---

## Task 12: Add Helm values entry

**Files:**
- Modify: `deployment/helm_chart/opik/values.yaml`

- [ ] **Step 1: Locate `components.backend.env`**

Run: `grep -n "components:\|backend:\|env:" deployment/helm_chart/opik/values.yaml | head -20` to find the correct block.

- [ ] **Step 2: Add the env entry**

Under `components.backend.env:` (the same map that holds DB URLs and Redis config), append:

```yaml
    # Default: 100
    # Deployment-level default rows-per-page for UI tables in v2.
    # Lower this to reduce initial render cost on trace-heavy workspaces.
    UI_DEFAULT_PAGE_SIZE: "100"
```

Match the indentation of surrounding env entries exactly (4 spaces before the key, aligned with the other siblings).

- [ ] **Step 3: Lint the Helm chart**

Run: `helm lint deployment/helm_chart/opik`
Expected: `1 chart(s) linted, 0 chart(s) failed`.

If `helm` isn't available locally, fall back to: `cd deployment/helm_chart/opik && grep -n "UI_DEFAULT_PAGE_SIZE" values.yaml` to confirm placement.

- [ ] **Step 4: Commit**

```bash
git add deployment/helm_chart/opik/values.yaml
git commit -m "[OPIK-6040] feat(helm): expose UI_DEFAULT_PAGE_SIZE as a backend env var

Operators can lower the default rows-per-page (e.g. to 25) without
rebuilding, and the backend substitutes the value into config.yml at
startup.
"
```

---

## Task 13: Manual verification

**Files:** none (runtime check)

- [ ] **Step 1: Boot the stack with a custom value**

In a dev shell:

```bash
export UI_DEFAULT_PAGE_SIZE=25
cd apps/opik-backend && mvn -q compile exec:java
```

Wait for the server to log `Server started`.

- [ ] **Step 2: Hit the endpoint**

In a second shell:

```bash
curl -s -b "cookie-file" http://localhost:8080/api/v1/private/ui-config/ | jq
```

Expected: `{ "default_page_size": 25 }`.

If auth is required, use whatever login cookie your local dev account has; matches the exact same auth profile as `/v1/private/toggles/`.

- [ ] **Step 3: Run the v2 frontend and load the Experiments page**

```bash
cd apps/opik-frontend && npm run dev
```

Navigate to `/<workspace>/experiments` in the v2 UI. Confirm:
- The pagination shows `1-25 of N` (not `1-100 of N`) on first load.
- Clicking into an experiment → item viewer also shows size 25.
- Changing the dropdown to 50 updates the URL to `?size=50`; refreshing keeps it at 50; navigating away and back (without the query param) drops you to 25 again.
- Browser devtools → Application → Local Storage → `experiments-pagination-size` is NOT written during this flow.

- [ ] **Step 4: Test fallback on backend failure**

With the frontend still running, stop the backend. Reload the Experiments page. The size should fall back to 100 and the page should still render without error (React Query returns no data → provider returns fallback).

Restart the backend for the next step.

- [ ] **Step 5: Document any findings**

If anything is off (e.g. a lingering `syncQueryWithLocalStorageOnInit` writing localStorage from another table on the same page), note it and fix inline before moving on. If everything works, no commit is needed for this step — it's a verification gate.

---

## Self-Review Checklist

- ✅ **Spec coverage:** all three v2 Experiments tables updated (Tasks 9, 10, 11); backend endpoint (Tasks 1–4); frontend plumbing (Tasks 5–8); Helm value (Task 12); manual verification of override / fallback / no-localStorage flows (Task 13). No spec item left unmapped.
- ✅ **Placeholder scan:** no TBD / TODO strings; every code block is complete; every test has real assertions.
- ✅ **Type consistency:** `UIConfig` (Java, `int defaultPageSize`) → JSON wire `default_page_size` (Jackson SnakeCaseStrategy) → `UIConfig` (TS, `{ default_page_size: number }`). Consumers rename on destructure (`{ default_page_size: defaultPageSize }`) so the internal variable stays idiomatic camelCase. Cache key is `["ui-config"]` in the single place that uses it.
- ✅ **Scope:** single-session-implementable, no decomposition needed.

---

## Risks

- **Stale `experiments-pagination-size` localStorage keys** left in users' browsers. Harmless — no code reads them after this change — but will show up in devtools forever. Cleanup is out of scope.
- **Helm indentation drift**: the env block indentation in `values.yaml` is picky; if Task 12 Step 2 is off, Helm templating can fail at render time. The `helm lint` check guards against it.
- **Concurrent edits to the three v2 files**: if another branch is refactoring these same files, Task 9/10/11 may conflict. No mitigation needed — this is a normal merge-conflict risk.

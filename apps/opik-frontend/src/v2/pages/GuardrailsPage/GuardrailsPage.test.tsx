import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { QueryParamProvider } from "use-query-params";
import { WindowHistoryAdapter } from "use-query-params/adapters/window";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PermissionsProvider } from "@/contexts/PermissionsContext";
import { TooltipProvider } from "@/ui/tooltip";
import { DEFAULT_PERMISSIONS } from "@/types/permissions";
import {
  GUARDRAIL_EXECUTION_SCOPE,
  GuardrailPolicy,
  GuardrailTypes,
} from "@/types/guardrails";
import GuardrailsPage from "./GuardrailsPage";

const mocks = vi.hoisted(() => ({
  policies: [] as GuardrailPolicy[] | undefined,
  byIdLookups: [] as { policyId: string; enabled: boolean }[],
}));

vi.mock("@/api/guardrail-policies/useGuardrailPoliciesList", () => ({
  default: () => ({
    data: mocks.policies && {
      content: mocks.policies,
      total: mocks.policies.length,
      sortable_by: [],
    },
    isPending: !mocks.policies,
    isPlaceholderData: false,
    isFetching: false,
    isError: false,
    refetch: vi.fn(),
  }),
}));
vi.mock("@/api/guardrail-policies/useGuardrailPolicyById", () => ({
  default: (
    { policyId }: { policyId: string },
    { enabled }: { enabled: boolean },
  ) => {
    mocks.byIdLookups.push({ policyId, enabled });
    return { data: undefined, error: null };
  },
}));
vi.mock("@/ui/use-toast", () => ({ useToast: () => ({ toast: vi.fn() }) }));
// The panel's own behaviour is covered by its tests; here it only shows which panel is open, for
// which policy, and with which taken names.
vi.mock(
  "@/v2/pages-shared/guardrails/GuardrailPolicyPanel/GuardrailPolicyPanel",
  () => ({
    default: ({
      mode,
      policy,
      takenNames,
      open,
    }: {
      mode: string;
      policy?: GuardrailPolicy;
      takenNames?: string[];
      open: boolean;
    }) =>
      open ? (
        <div
          data-testid={`panel-${mode}`}
          data-policy={policy?.name}
          data-taken-names={(takenNames ?? []).join("|")}
        />
      ) : null,
  }),
);

const createPolicy = (
  index: number,
  name = `policy-${index}`,
): GuardrailPolicy => ({
  id: `0195b8e0-0000-7000-8000-00000000000${index}`,
  name,
  execution_scope: GUARDRAIL_EXECUTION_SCOPE.ON_REQUEST,
  rules: [{ type: GuardrailTypes.PROMPT_INJECTION, config: {} }],
  created_at: "2026-11-06T10:00:00Z",
  created_by: "admin",
  last_updated_at: "2026-11-06T10:00:00Z",
  last_updated_by: "admin",
});

const FIRST = createPolicy(1);
const SECOND = createPolicy(2);

const renderPage = (permissions = DEFAULT_PERMISSIONS) => {
  const queryClient = new QueryClient();
  const page = () => (
    <QueryClientProvider client={queryClient}>
      <QueryParamProvider adapter={WindowHistoryAdapter}>
        <PermissionsProvider value={permissions}>
          <TooltipProvider>
            <GuardrailsPage />
          </TooltipProvider>
        </PermissionsProvider>
      </QueryParamProvider>
    </QueryClientProvider>
  );
  const rendered = render(page());
  return { rerender: () => rendered.rerender(page()) };
};

const openRowMenu = (policyName: string) => {
  const row = screen.getByText(policyName).closest("tr")!;
  // Radix opens dropdowns on pointerdown, not click.
  fireEvent.pointerDown(
    within(row).getByText("Actions menu").closest("button")!,
    new PointerEvent("pointerdown", { bubbles: true, button: 0 }),
  );
};

describe("GuardrailsPage", () => {
  beforeEach(() => {
    window.history.replaceState(null, "", "/");
    mocks.policies = [FIRST, SECOND];
    mocks.byIdLookups = [];
  });

  it("keeps the open policy's panel while a refetch moves the policy off the page", () => {
    window.history.replaceState(null, "", `/?policyId=${FIRST.id}`);
    const { rerender } = renderPage();
    expect(screen.getByTestId("panel-edit").dataset.policy).toBe(FIRST.name);

    // Someone else's new policy pushes it to the next page; the lookup by id has not answered yet.
    mocks.policies = [createPolicy(3), SECOND];
    rerender();

    expect(screen.getByTestId("panel-edit").dataset.policy).toBe(FIRST.name);
    expect(mocks.byIdLookups.at(-1)).toEqual({
      policyId: FIRST.id,
      enabled: true,
    });
  });

  it("does not look up a linked policy by id before the list has answered", () => {
    window.history.replaceState(null, "", `/?policyId=${FIRST.id}`);
    mocks.policies = undefined;
    const { rerender } = renderPage();

    mocks.policies = [FIRST, SECOND];
    rerender();

    expect(mocks.byIdLookups.some(({ enabled }) => enabled)).toBe(false);
    expect(screen.getByTestId("panel-edit").dataset.policy).toBe(FIRST.name);
  });

  it("opens Edit from the row menu in the page's panel, with the id in the URL", async () => {
    const { rerender } = renderPage();

    openRowMenu(SECOND.name);
    fireEvent.click(screen.getByRole("menuitem", { name: "Edit" }));

    await waitFor(() =>
      expect(window.location.search).toContain(`policyId=${SECOND.id}`),
    );
    // The app's router re-renders on a URL change; the window adapter used here does not.
    rerender();

    expect(screen.getByTestId("panel-edit").dataset.policy).toBe(SECOND.name);
  });

  it("duplicates against the names on the page when Duplicate is clicked", () => {
    const { rerender } = renderPage();
    mocks.policies = [createPolicy(3, `${FIRST.name} (copy)`), FIRST, SECOND];
    rerender();

    openRowMenu(FIRST.name);
    fireEvent.click(screen.getByRole("menuitem", { name: "Duplicate" }));

    const panel = screen.getByTestId("panel-duplicate");
    expect(panel.dataset.policy).toBe(FIRST.name);
    expect(panel.dataset.takenNames).toContain(`${FIRST.name} (copy)`);
  });

  it("shows no row checkboxes without the permission to change policies", () => {
    renderPage({
      ...DEFAULT_PERMISSIONS,
      permissions: {
        ...DEFAULT_PERMISSIONS.permissions,
        canConfigureWorkspaceSettings: false,
      },
    });

    expect(screen.getByText(FIRST.name)).toBeTruthy();
    expect(screen.queryAllByRole("checkbox")).toHaveLength(0);
  });
});

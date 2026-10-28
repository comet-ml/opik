import React from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import usePluginsStore from "@/store/PluginsStore";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import useGuardrailsEnabled from "./useGuardrailsEnabled";

const { toggles } = vi.hoisted(() => ({
  toggles: {
    current: {} as Record<string, unknown>,
  },
}));

vi.mock("@/api/feature-toggle/useFeatureToggle", () => ({
  default: () => toggles.current,
}));
vi.mock("@/store/AppStore", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/store/AppStore")>()),
  useActiveWorkspaceName: () => "workspace",
}));

const togglesLoaded = (guardrailsOn: boolean) => ({
  data: { [FeatureToggleKeys.GUARDRAILS_VALIDATIONS_ENABLED]: guardrailsOn },
  isPending: false,
  isError: false,
  refetch: vi.fn(),
});

const renderEnabled = () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return renderHook(() => useGuardrailsEnabled(), {
    wrapper: ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    ),
  });
};

describe("useGuardrailsEnabled", () => {
  beforeEach(() => {
    usePluginsStore.setState({ getGuardrailsEnabled: null });
  });

  afterEach(() => {
    usePluginsStore.setState({ getGuardrailsEnabled: null });
  });

  it("is pending while the toggles load", () => {
    toggles.current = {
      data: undefined,
      isPending: true,
      isError: false,
      refetch: vi.fn(),
    };

    expect(renderEnabled().result.current).toMatchObject({
      isEnabled: false,
      isPending: true,
      isError: false,
    });
  });

  it("reports a failed toggles request as an error with a retry, not as disabled", () => {
    const refetch = vi.fn();
    toggles.current = {
      data: undefined,
      isPending: false,
      isError: true,
      refetch,
    };

    const { result } = renderEnabled();

    expect(result.current).toMatchObject({
      isEnabled: false,
      isPending: false,
      isError: true,
    });
    result.current.refetch();
    expect(refetch).toHaveBeenCalled();
  });

  it("is disabled when the deployment does not run guardrails, without asking the plugin", () => {
    const getGuardrailsEnabled = vi.fn();
    usePluginsStore.setState({ getGuardrailsEnabled });
    toggles.current = togglesLoaded(false);

    expect(renderEnabled().result.current).toMatchObject({
      isEnabled: false,
      isPending: false,
      isError: false,
    });
    expect(getGuardrailsEnabled).not.toHaveBeenCalled();
  });

  it("lets the toggle decide when no platform plugin can say more", () => {
    toggles.current = togglesLoaded(true);

    expect(renderEnabled().result.current).toMatchObject({
      isEnabled: true,
      isPending: false,
      isError: false,
    });
  });

  it.each([
    [true, true],
    [false, false],
  ])(
    "follows the organization's enablement from the plugin (%s)",
    async (organizationEnabled, expected) => {
      usePluginsStore.setState({
        getGuardrailsEnabled: vi.fn().mockResolvedValue(organizationEnabled),
      });
      toggles.current = togglesLoaded(true);

      const { result } = renderEnabled();

      await waitFor(() => expect(result.current.isPending).toBe(false));
      expect(result.current).toMatchObject({
        isEnabled: expected,
        isError: false,
      });
    },
  );

  it("reports a failed organization lookup as an error, not as disabled", async () => {
    usePluginsStore.setState({
      getGuardrailsEnabled: vi.fn().mockRejectedValue(new Error("down")),
    });
    toggles.current = togglesLoaded(true);

    const { result } = renderEnabled();

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.isEnabled).toBe(false);
  });
});

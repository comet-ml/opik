import React from "react";
import { describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const del = vi.fn();
const toast = vi.fn();

vi.mock("@/api/api", () => ({
  default: { delete: (url: string) => del(url) },
  GUARDRAIL_POLICIES_REST_ENDPOINT: "/v1/private/guardrails/policies/",
  GUARDRAIL_POLICIES_KEY: "guardrail-policies",
  GUARDRAIL_POLICY_KEY: "guardrail-policy",
}));
vi.mock("@/ui/use-toast", () => ({ useToast: () => ({ toast }) }));

import useGuardrailPolicyBatchDeleteMutation from "./useGuardrailPolicyBatchDeleteMutation";

const forbidden = (message: string) =>
  Object.assign(new Error("Request failed with status code 403"), {
    response: { data: { message } },
  });

describe("useGuardrailPolicyBatchDeleteMutation", () => {
  it("reports every failed deletion and drops the deleted policy from the cache", async () => {
    del.mockImplementation((url: string) =>
      url.endsWith("p1")
        ? Promise.resolve({})
        : Promise.reject(forbidden("User not allowed to delete policies")),
    );
    const queryClient = new QueryClient({
      defaultOptions: { mutations: { retry: false } },
    });
    const removed: unknown[] = [];
    queryClient.removeQueries = vi.fn(({ queryKey }) => {
      removed.push(queryKey);
    }) as never;
    queryClient.invalidateQueries = vi.fn(() => Promise.resolve()) as never;
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
    const { result } = renderHook(
      () => useGuardrailPolicyBatchDeleteMutation(),
      { wrapper },
    );

    await act(async () => {
      result.current.mutate({ ids: ["p1", "p2", "p3"] });
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(del).toHaveBeenCalledTimes(3);
    expect(toast).toHaveBeenCalledWith({
      title: "Error",
      description:
        "2 of 3 policies could not be deleted: User not allowed to delete policies",
      variant: "destructive",
    });
    expect(removed).toEqual([["guardrail-policy", { policyId: "p1" }]]);
    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["guardrail-policies"],
    });
  });
});

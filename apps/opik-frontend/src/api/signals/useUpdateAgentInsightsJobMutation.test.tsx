import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { AGENT_INSIGHTS_JOB_STATUS } from "@/types/signals";

const patch = vi.fn();
const post = vi.fn();

vi.mock("@/api/api", () => ({
  default: {
    patch: (...args: unknown[]) => patch(...args),
    post: (...args: unknown[]) => post(...args),
  },
  AGENT_INSIGHTS_JOB_KEY: "agent-insights-job",
  AGENT_INSIGHTS_REST_ENDPOINT: "/v1/private/agent-insights/",
}));

vi.mock("@/ui/use-toast", () => ({ useToast: () => ({ toast: vi.fn() }) }));

import useUpdateAgentInsightsJobMutation from "./useUpdateAgentInsightsJobMutation";

const wrapper = ({ children }: { children: React.ReactNode }) => (
  <QueryClientProvider client={new QueryClient()}>
    {children}
  </QueryClientProvider>
);

const URL = "/v1/private/agent-insights/jobs/p1";

describe("useUpdateAgentInsightsJobMutation", () => {
  beforeEach(() => {
    patch.mockReset();
    post.mockReset();
  });

  it("creates the job and retries when the project has none yet", async () => {
    patch
      .mockRejectedValueOnce({ response: { status: 404 } })
      .mockResolvedValueOnce({ data: { status: "enabled" } });
    post.mockResolvedValueOnce({ data: {} });

    const { result } = renderHook(() => useUpdateAgentInsightsJobMutation(), {
      wrapper,
    });
    result.current.mutate({
      projectId: "p1",
      status: AGENT_INSIGHTS_JOB_STATUS.enabled,
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(post).toHaveBeenCalledWith(URL);
    expect(patch).toHaveBeenCalledTimes(2);
    expect(patch).toHaveBeenLastCalledWith(URL, { status: "enabled" });
  });

  it("doesn't create a job on other errors", async () => {
    patch.mockRejectedValueOnce({ response: { status: 403 } });

    const { result } = renderHook(() => useUpdateAgentInsightsJobMutation(), {
      wrapper,
    });
    result.current.mutate({
      projectId: "p1",
      status: AGENT_INSIGHTS_JOB_STATUS.enabled,
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(post).not.toHaveBeenCalled();
  });
});

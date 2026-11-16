import { describe, expect, it } from "vitest";
import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { TRACES_KEY } from "@/api/api";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";
import { generateUpdateMutation, setTracesCache } from "@/lib/feedback-scores";

describe("setTracesCache", () => {
  it("updates the traces list and leaves other [TRACES_KEY] queries alone", async () => {
    const queryClient = new QueryClient();
    const listKey = [TRACES_KEY, { projectId: "p" }];
    const existsKey = [TRACES_KEY, { projectId: "p" }, "exists"];
    queryClient.setQueryData(listKey, { content: [{ id: "t1" }], total: 1 });
    queryClient.setQueryData(existsKey, { exists: true });
    // setTracesCache only touches active (enabled, observed) queries
    const unsubscribers = [listKey, existsKey].map((queryKey) =>
      new QueryObserver(queryClient, {
        queryKey,
        queryFn: () => new Promise(() => {}),
        staleTime: Infinity,
      }).subscribe(() => {}),
    );

    await setTracesCache(
      queryClient,
      { traceId: "t1" },
      generateUpdateMutation({
        name: "User feedback",
        value: 1,
        source: FEEDBACK_SCORE_TYPE.ui,
      }),
    );
    await new Promise((resolve) => setTimeout(resolve));

    expect(queryClient.getQueryData(listKey)).toMatchObject({
      content: [{ id: "t1", feedback_scores: [{ value: 1 }] }],
    });
    expect(queryClient.getQueryData(existsKey)).toEqual({ exists: true });
    unsubscribers.forEach((unsubscribe) => unsubscribe());
  });
});

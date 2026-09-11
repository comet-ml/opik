import React from "react";
import { describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { Thread } from "@/types/traces";
import { ThreadStatus } from "@/types/thread";
import ItemsSidebar from "./ItemsSidebar";

const mockUseSMEFlow = vi.hoisted(() => vi.fn());
vi.mock("../SMEFlowContext", async () => ({
  ITEM_STATE: (await import("@/lib/annotation-queues")).ITEM_STATE,
  useSMEFlow: mockUseSMEFlow,
}));

describe("ItemsSidebar thread previews", () => {
  it.each([0, false, null])(
    "renders thread output %j without a truthiness gate",
    (last_message) => {
      const thread: Thread = {
        id: "thread-1",
        thread_model_id: "model-1",
        project_id: "project-1",
        start_time: "2026-09-11T00:00:00Z",
        end_time: "2026-09-11T00:00:01Z",
        duration: 1,
        first_message: "Question",
        last_message,
        number_of_messages: 2,
        created_at: "2026-09-11T00:00:00Z",
        last_updated_at: "2026-09-11T00:00:01Z",
        created_by: "reviewer",
        status: ThreadStatus.INACTIVE,
      };
      mockUseSMEFlow.mockReturnValue({
        queueItems: [thread],
        currentIndex: 0,
        itemStates: {},
        shuffledItemIds: [thread.thread_model_id],
        navigateToItem: vi.fn(),
      });
      const html = renderToStaticMarkup(<ItemsSidebar />);
      expect(html).toContain("Question");
      if (last_message === null) {
        expect(html).not.toContain(">null</");
      } else {
        expect(html).toContain(`>${last_message}</`);
      }
    },
  );
});

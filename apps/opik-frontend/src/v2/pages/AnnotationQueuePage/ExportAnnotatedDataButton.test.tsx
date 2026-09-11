import React from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { csv2json } from "json-2-csv";
import { TooltipProvider } from "@/ui/tooltip";
import {
  ANNOTATION_QUEUE_SCOPE,
  AnnotationQueue,
} from "@/types/annotation-queues";
import { JsonNode } from "@/types/shared";
import { Thread } from "@/types/traces";
import { ThreadStatus } from "@/types/thread";
import ExportAnnotatedDataButton from "./ExportAnnotatedDataButton";

const mocks = vi.hoisted(() => ({
  saveAs: vi.fn<(blob: Blob, name: string) => void>(),
  refetchThreads: vi.fn(),
  refetchTraces: vi.fn(),
}));
vi.mock("file-saver", () => ({ saveAs: mocks.saveAs }));
vi.mock("@/api/traces/useThreadsList", () => ({
  default: () => ({ refetch: mocks.refetchThreads }),
}));
vi.mock("@/api/traces/useTracesList", () => ({
  default: () => ({ refetch: mocks.refetchTraces }),
}));
vi.mock("@/contexts/feature-toggles-provider", () => ({
  useIsFeatureEnabled: () => true,
}));

const queue: AnnotationQueue = {
  id: "queue-1",
  project_id: "project-1",
  project_name: "Project",
  name: "Review",
  scope: ANNOTATION_QUEUE_SCOPE.THREAD,
  comments_enabled: true,
  feedback_definition_names: [],
  items_count: 1,
  created_at: "2026-09-11T00:00:00Z",
  created_by: "reviewer",
  last_updated_at: "2026-09-11T00:00:00Z",
  last_updated_by: "reviewer",
  last_scored_at: "2026-09-11T00:00:00Z",
};
const thread = (first_message?: JsonNode, last_message?: JsonNode): Thread => ({
  id: "thread-1",
  thread_model_id: "model-1",
  project_id: "project-1",
  start_time: "2026-09-11T00:00:00Z",
  end_time: "2026-09-11T00:00:01Z",
  duration: 1,
  first_message,
  last_message,
  number_of_messages: 2,
  status: ThreadStatus.INACTIVE,
  created_at: "2026-09-11T00:00:00Z",
  last_updated_at: "2026-09-11T00:00:01Z",
  created_by: "reviewer",
});

async function exportFile(format: "JSON" | "CSV") {
  render(
    <TooltipProvider>
      <ExportAnnotatedDataButton annotationQueue={queue} />
    </TooltipProvider>,
  );
  await act(async () => {
    fireEvent.keyDown(screen.getByRole("button", { name: "Export queue" }), {
      key: "ArrowDown",
    });
  });
  const menuItem = await screen.findByRole("menuitem", {
    name: `As ${format}`,
  });
  await act(async () => {
    fireEvent.click(menuItem);
  });
  await waitFor(() => expect(mocks.saveAs).toHaveBeenCalledOnce());
  const [blob, filename] = mocks.saveAs.mock.calls[0];
  expect(filename).toBe(`annotated-thread-Review.${format.toLowerCase()}`);
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () =>
      typeof reader.result === "string"
        ? resolve(reader.result)
        : reject(new Error("Expected text"));
    reader.onerror = reject;
    reader.readAsText(blob);
  });
}

describe("thread annotation exports", () => {
  beforeEach(() => vi.clearAllMocks());

  it("preserves omitted message fields from the API", async () => {
    mocks.refetchThreads.mockResolvedValue({ data: { content: [thread()] } });
    expect(JSON.parse(await exportFile("JSON"))).toEqual([{ id: "thread-1" }]);
  });

  const first_message = {
    messages: [
      { role: "system", content: "Instructions" },
      { role: "user", content: "Question" },
    ],
  };
  const last_message = {
    messages: [
      {
        role: "assistant",
        content: "Earlier answer",
        tool_calls: [
          {
            id: "call-1",
            function: { name: "search", arguments: '{"query":"topic"}' },
          },
        ],
      },
      { role: "tool", tool_call_id: "call-1", content: "Tool result" },
      {
        role: "assistant",
        content: "Final answer",
        contents: [{ image: { url: "https://example.com/image.png" } }],
      },
    ],
  };

  it("preserves all turns, tool calls, and media in JSON", async () => {
    mocks.refetchThreads.mockResolvedValue({
      data: { content: [thread(first_message, last_message)] },
    });
    expect(JSON.parse(await exportFile("JSON"))).toEqual([
      { id: "thread-1", first_message, last_message },
    ]);
  });

  it("preserves structured message fields in CSV cells", async () => {
    mocks.refetchThreads.mockResolvedValue({
      data: { content: [thread(first_message, last_message)] },
    });
    const rows = csv2json(await exportFile("CSV"));
    expect(rows).toEqual([{ id: "thread-1", first_message, last_message }]);
  });

  it.each([0, false, null])(
    "preserves scalar/null payload %j in JSON",
    async (value) => {
      mocks.refetchThreads.mockResolvedValue({
        data: { content: [thread(value, value)] },
      });
      expect(JSON.parse(await exportFile("JSON"))).toEqual([
        { id: "thread-1", first_message: value, last_message: value },
      ]);
    },
  );
});

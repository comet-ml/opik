import { describe, it, expect, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import { ExperimentItem } from "@/types/datasets";
import { UnifiedMediaItem } from "@/hooks/useUnifiedMedia";
import CompareExperimentsViewer from "./CompareExperimentsViewer";

vi.mock("@/api/datasets/useExperimentById", () => ({
  default: () => ({ data: { name: "exp-a", project_id: "project-1" } }),
}));

vi.mock("@/api/traces/useTraceById", () => ({
  default: () => ({ data: undefined }),
}));

vi.mock("@/api/attachments/useAttachmentsList", () => ({
  default: () => ({ data: undefined, isLoading: false }),
}));

vi.mock("@/lib/media", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/media")>()),
  detectAdditionalMedia: (_: unknown, existing: unknown) =>
    Promise.resolve(existing),
}));

vi.mock(
  "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/AttachmentsList",
  () => ({
    default: ({ media }: { media: UnifiedMediaItem[] }) => (
      <ul data-testid="attachments">
        {media.map((item) => (
          <li key={item.id}>{item.placeholder}</li>
        ))}
      </ul>
    ),
  }),
);

vi.mock(
  "@/v2/pages-shared/experiments/ExperimentMessagesViewer/ExperimentMessagesViewer",
  () => ({
    default: ({ input, output }: { input: unknown; output: unknown }) => (
      <pre data-testid="messages">{JSON.stringify({ input, output })}</pre>
    ),
  }),
);

vi.mock(
  "@/v2/pages-shared/ExperimentFeedbackScoresViewer/ExperimentFeedbackScoresViewer",
  () => ({ default: () => null }),
);

vi.mock("./DataTab/ExperimentCommentsViewer", () => ({
  default: () => null,
}));

const PNG_A =
  "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";
const PNG_B =
  "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

const buildItem = (): ExperimentItem =>
  ({
    id: "item-1",
    experiment_id: "exp-1",
    dataset_item_id: "ds-item-1",
    trace_id: "trace-1",
    input: {
      messages: [
        { role: "user", content: `What is in this picture? ${PNG_A}` },
      ],
    },
    output: { output: `Here is the edited picture: ${PNG_B}` },
  }) as ExperimentItem;

describe("CompareExperimentsViewer", () => {
  it("labels the thumbnail strip with the placeholders written into the conversation", async () => {
    render(
      <TooltipProvider>
        <CompareExperimentsViewer
          experimentItem={buildItem()}
          openTrace={vi.fn()}
          sectionIdx={0}
        />
      </TooltipProvider>,
    );

    const conversation = await screen.findByTestId("messages");
    const textPlaceholders = conversation.textContent?.match(/\[image_\d+\]/g);

    await waitFor(() =>
      expect(screen.getByTestId("attachments").textContent).not.toBe(""),
    );
    const stripPlaceholders = [
      ...screen.getByTestId("attachments").querySelectorAll("li"),
    ].map((li) => li.textContent);

    // Input and output are numbered together, so the output's image is the
    // second one; the strip must carry both, under the same tags.
    expect(textPlaceholders).toEqual(["[image_0]", "[image_1]"]);
    expect(stripPlaceholders).toEqual(["[image_0]", "[image_1]"]);
  });
});

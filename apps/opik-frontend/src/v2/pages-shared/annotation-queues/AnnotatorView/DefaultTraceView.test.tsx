import React from "react";
import { describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { Trace } from "@/types/traces";
import { prettifyMessage } from "@/lib/traces";
import CodeBlock from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/CodeBlock/CodeBlock";
import DefaultTraceView from "./DefaultTraceView";

vi.mock("@/hooks/useUnifiedMedia", () => ({
  useUnifiedMedia: (trace: Trace) => ({
    media: [],
    transformedInput: trace.input,
    transformedOutput: trace.output,
  }),
}));
vi.mock(
  "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/AttachmentsList",
  () => ({ default: () => null }),
);
vi.mock(
  "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/CodeBlock/CodeBlock",
  () => ({
    default: ({
      data,
      prettifyConfig,
    }: React.ComponentProps<typeof CodeBlock>) => {
      const pretty = prettifyMessage(data, {
        type: prettifyConfig?.fieldType ?? "input",
        openInferenceHint: prettifyConfig?.openInferenceHint,
        openInferenceInput: prettifyConfig?.openInferenceInput,
      });
      return (
        <span>
          {typeof pretty.message === "string"
            ? pretty.message
            : JSON.stringify(pretty.message)}
        </span>
      );
    },
  }),
);

const trace: Trace = {
  id: "trace-1",
  project_id: "project-1",
  name: "Annotation example",
  input: {
    "llm.input_messages.0.message.content": "Question",
    "llm.output_messages.0.message.content": "Historical answer",
  },
  output: {},
  metadata: {},
  start_time: "2026-10-29T00:00:00Z",
  end_time: "2026-10-29T00:00:01Z",
  duration: 1,
  created_at: "2026-10-29T00:00:00Z",
  last_updated_at: "2026-10-29T00:00:01Z",
  tags: [],
  comments: [],
};

describe("DefaultTraceView OpenInference previews", () => {
  it.each([
    { output: {}, expected: "Historical answer" },
    { output: { answer: "Current answer" }, expected: "Current answer" },
  ])("renders $expected in the annotation fallback", ({ output, expected }) => {
    const html = renderToStaticMarkup(
      <DefaultTraceView trace={{ ...trace, output }} />,
    );

    expect(html).toContain("Question");
    expect(html).toContain(expected);
    if (expected === "Current answer") {
      expect(html).not.toContain("Historical answer");
    }
  });
});

import React from "react";
import { describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { Trace } from "@/types/traces";
import TraceMessage from "./TraceMessage";

vi.mock("./LikeFeedback", () => ({ default: () => null }));
const trace: Trace = {
  id: "trace-1",
  project_id: "project-1",
  name: "Example",
  input: {},
  output: {},
  metadata: {},
  start_time: "2026-09-10T00:00:00Z",
  end_time: "2026-09-10T00:00:01Z",
  duration: 1,
  created_at: "2026-09-10T00:00:00Z",
  last_updated_at: "2026-09-10T00:00:01Z",
  tags: [],
  comments: [],
};
describe("TraceMessage previews", () => {
  it("renders legacy output recovered from input", () => {
    const html = renderToStaticMarkup(
      <TraceMessage
        trace={{
          ...trace,
          input: {
            "llm.output_messages.0.message.content": "Recovered answer",
          },
        }}
        handleOpenTrace={() => {}}
      />,
    );
    expect(html).toContain("Recovered answer");
  });
  it("prefers the current answer over the historical output", () => {
    const html = renderToStaticMarkup(
      <TraceMessage
        trace={{
          ...trace,
          input: {
            "llm.input_messages.0.message.content": "Question",
            "llm.output_messages.0.message.content": "Stale answer",
          },
          output: { answer: "Current answer" },
        }}
        handleOpenTrace={() => {}}
      />,
    );
    expect(html).toContain("Current answer");
    expect(html).not.toContain("Stale answer");
  });
});

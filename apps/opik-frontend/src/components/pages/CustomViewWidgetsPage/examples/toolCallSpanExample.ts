import type { ViewTree, SourceData } from "@/lib/data-view/core/types";

/**
 * SPAN-LEVEL VIEW: Tool Call Span
 *
 * Based on Figma node 245-15436
 * Shows a single tool call span with:
 * - Level2Container with summary (tool name + duration)
 * - Link to span (LinkButton)
 * - Stats line: "Model: Gpt-5-mini • Duration: 0.01s"
 * - Input JSON code block
 * - Output JSON code block
 *
 * Note: This is a span-level view (Level2Container at root), designed to be
 * embedded within a trace view. Level2 at root is valid for span detail views.
 * L2 containers can only contain leaf widgets (no InlineRow, no Container).
 * LinkButton and stats text are stacked vertically as separate leaf widgets.
 */
export const toolCallSpanExample = {
  title: "Tool Call Span (Level2Container)",
  tree: {
    version: 1,
    root: "root",
    nodes: {
      root: {
        id: "root",
        type: "Level2Container",
        props: {
          summary: { path: "/summary" },
          defaultOpen: false,
          icon: "tool",
          status: "success",
          duration: { path: "/duration" },
        },
        children: ["spanLink", "statsLine", "inputCode", "outputCode"],
        parentKey: null,
      },
      spanLink: {
        id: "spanLink",
        type: "LinkButton",
        props: {
          type: "span",
          id: { path: "/spanId" },
          label: "Span",
        },
        children: undefined,
        parentKey: "root",
      },
      statsLine: {
        id: "statsLine",
        type: "Text",
        props: {
          value: { path: "/statsLine" },
          variant: "body-small",
        },
        children: undefined,
        parentKey: "root",
      },
      inputCode: {
        id: "inputCode",
        type: "Code",
        props: {
          content: { path: "/inputJson" },
          language: "json",
          label: "Input",
          showCopy: true,
        },
        children: undefined,
        parentKey: "root",
      },
      outputCode: {
        id: "outputCode",
        type: "Code",
        props: {
          content: { path: "/outputJson" },
          language: "json",
          label: "Output",
          showCopy: true,
        },
        children: undefined,
        parentKey: "root",
      },
    },
    meta: {
      name: "Tool Call Span",
      description: "Collapsible tool call with input/output JSON",
    },
  } satisfies ViewTree,
  sourceData: {
    summary: "check_query_type",
    spanId: "span-001-abc",
    statsLine: "Model: Gpt-5-mini • Duration: 0.01s",
    duration: "0.01s",
    inputJson: JSON.stringify(
      {
        query: "can you give me a link to educational support contact",
        locale: "en_US",
        context: {
          session_id: "sess_12345",
          user_type: "student",
        },
      },
      null,
      2,
    ),
    outputJson: JSON.stringify(
      {
        query_type: "support_request",
        detected_intent: "contact_info",
        confidence: 0.95,
      },
      null,
      2,
    ),
  } satisfies SourceData,
};

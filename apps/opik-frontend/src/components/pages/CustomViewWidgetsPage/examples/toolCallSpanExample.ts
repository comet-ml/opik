import type { ViewTree, SourceData } from "@/lib/data-view/core/types";

/**
 * SPAN-LEVEL VIEW: Tool Call Span
 *
 * Based on Figma node 245-15436
 * Shows a single tool call span with:
 * - Level2Container with summary (tool name + duration)
 * - Link to span
 * - Model info
 * - Duration
 * - Input JSON code block
 * - Output JSON code block
 *
 * Note: This is a span-level view (Level2Container at root), designed to be
 * embedded within a trace view. Level2 at root is valid for span detail views.
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
          defaultOpen: true,
          icon: "tool",
          status: "success",
          duration: { path: "/duration" },
        },
        children: ["metaRow", "inputCode", "outputCode"],
        parentKey: null,
      },
      metaRow: {
        id: "metaRow",
        type: "InlineRow",
        props: {},
        children: [
          "spanLink",
          "modelLabel",
          "modelValue",
          "durationLabel",
          "durationValue",
        ],
        parentKey: "root",
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
        parentKey: "metaRow",
      },
      modelLabel: {
        id: "modelLabel",
        type: "Label",
        props: { text: "Model:" },
        children: undefined,
        parentKey: "metaRow",
      },
      modelValue: {
        id: "modelValue",
        type: "Text",
        props: { value: { path: "/model" }, variant: "body" },
        children: undefined,
        parentKey: "metaRow",
      },
      durationLabel: {
        id: "durationLabel",
        type: "Label",
        props: { text: "Duration:" },
        children: undefined,
        parentKey: "metaRow",
      },
      durationValue: {
        id: "durationValue",
        type: "Text",
        props: { value: { path: "/duration" }, variant: "body" },
        children: undefined,
        parentKey: "metaRow",
      },
      inputCode: {
        id: "inputCode",
        type: "Code",
        props: {
          content: { path: "/inputJson" },
          language: "json",
          label: "Input",
          showLineNumbers: false,
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
          showLineNumbers: false,
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
    summary: "generate_standalone_question",
    spanId: "span-001-abc",
    model: "gpt-5-mini",
    duration: "0.01s",
    inputJson: JSON.stringify(
      {
        question: "can you give me a link to educational support contact",
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
        question_first_language:
          "Can you provide a link to educational support contact?",
        detected_intent: "support_request",
        confidence: 0.95,
      },
      null,
      2,
    ),
  } satisfies SourceData,
};

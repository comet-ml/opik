import type { ViewTree, SourceData } from "@/lib/data-view/core/types";

/**
 * COMPLETE TRACE VIEW: Trace with Tool Calls
 *
 * Based on Figma node 245-15436
 * Shows a complete trace with:
 * - Timestamp header
 * - Input/Output TextBlocks at root level
 * - View trace link
 * - Tool calls section with Level2Containers as direct children of Level1Container
 *
 * Note: Tool calls are direct children of Level1Container (not nested Level2Containers)
 * per schema generation rules (no Level2 inside Level2).
 */
export const traceWithToolCallsExample = {
  title: "Trace with Expanded Tool Calls",
  tree: {
    version: 1,
    root: "root",
    nodes: {
      root: {
        id: "root",
        type: "Container",
        props: { layout: "stack", gap: "md", padding: "none" },
        children: [
          "timestamp",
          "input",
          "output",
          "traceLink",
          "toolCallsWrapper",
        ],
        parentKey: null,
      },
      timestamp: {
        id: "timestamp",
        type: "Text",
        props: { value: { path: "/timestamp" }, variant: "caption" },
        children: undefined,
        parentKey: "root",
      },
      input: {
        id: "input",
        type: "TextBlock",
        props: {
          label: "Input",
          content: { path: "/input" },
        },
        children: undefined,
        parentKey: "root",
      },
      output: {
        id: "output",
        type: "TextBlock",
        props: {
          label: "Output",
          content: { path: "/output" },
          maxLines: 3,
          expandable: true,
        },
        children: undefined,
        parentKey: "root",
      },
      traceLink: {
        id: "traceLink",
        type: "LinkButton",
        props: {
          type: "trace",
          id: { path: "/traceId" },
          label: "View trace",
        },
        children: undefined,
        parentKey: "root",
      },
      // Tool Calls Wrapper (Level1Container with title for the section)
      toolCallsWrapper: {
        id: "toolCallsWrapper",
        type: "Level1Container",
        props: { title: "Tool calls (4)" },
        children: ["toolCall1", "toolCall2", "toolCall3", "toolCall4"],
        parentKey: "root",
      },
      // Tool Call 1: generate_standalone_question
      toolCall1: {
        id: "toolCall1",
        type: "Level2Container",
        props: {
          summary: { path: "/toolCalls/0/summary" },
          defaultOpen: true,
          icon: "tool",
          status: "success",
          duration: { path: "/toolCalls/0/duration" },
        },
        children: ["tc1Meta", "tc1Input", "tc1Output"],
        parentKey: "toolCallsWrapper",
      },
      tc1Meta: {
        id: "tc1Meta",
        type: "InlineRow",
        props: {},
        children: [
          "tc1SpanLink",
          "tc1ModelLabel",
          "tc1Model",
          "tc1DurLabel",
          "tc1Dur",
        ],
        parentKey: "toolCall1",
      },
      tc1SpanLink: {
        id: "tc1SpanLink",
        type: "LinkButton",
        props: {
          type: "span",
          id: { path: "/toolCalls/0/spanId" },
          label: "Span",
        },
        children: undefined,
        parentKey: "tc1Meta",
      },
      tc1ModelLabel: {
        id: "tc1ModelLabel",
        type: "Label",
        props: { text: "Model:" },
        children: undefined,
        parentKey: "tc1Meta",
      },
      tc1Model: {
        id: "tc1Model",
        type: "Text",
        props: { value: { path: "/toolCalls/0/model" }, variant: "body" },
        children: undefined,
        parentKey: "tc1Meta",
      },
      tc1DurLabel: {
        id: "tc1DurLabel",
        type: "Label",
        props: { text: "Duration:" },
        children: undefined,
        parentKey: "tc1Meta",
      },
      tc1Dur: {
        id: "tc1Dur",
        type: "Text",
        props: {
          value: { path: "/toolCalls/0/modelDuration" },
          variant: "body",
        },
        children: undefined,
        parentKey: "tc1Meta",
      },
      tc1Input: {
        id: "tc1Input",
        type: "Code",
        props: {
          content: { path: "/toolCalls/0/inputJson" },
          language: "json",
          label: "Input",
          showLineNumbers: false,
        },
        children: undefined,
        parentKey: "toolCall1",
      },
      tc1Output: {
        id: "tc1Output",
        type: "Code",
        props: {
          content: { path: "/toolCalls/0/outputJson" },
          language: "json",
          label: "Output",
          showLineNumbers: false,
        },
        children: undefined,
        parentKey: "toolCall1",
      },
      // Tool Call 2: Retrieve
      toolCall2: {
        id: "toolCall2",
        type: "Level2Container",
        props: {
          summary: { path: "/toolCalls/1/summary" },
          defaultOpen: false,
          icon: "retrieval",
          status: "success",
          duration: { path: "/toolCalls/1/duration" },
        },
        children: ["tc2Stats", "tc2Indexes"],
        parentKey: "toolCallsWrapper",
      },
      tc2Stats: {
        id: "tc2Stats",
        type: "InlineRow",
        props: {},
        children: ["tc2IdxLabel", "tc2IdxVal", "tc2DocsLabel", "tc2DocsVal"],
        parentKey: "toolCall2",
      },
      tc2IdxLabel: {
        id: "tc2IdxLabel",
        type: "Label",
        props: { text: "Indexes queried:" },
        children: undefined,
        parentKey: "tc2Stats",
      },
      tc2IdxVal: {
        id: "tc2IdxVal",
        type: "Number",
        props: { value: { path: "/toolCalls/1/indexesQueried" }, size: "sm" },
        children: undefined,
        parentKey: "tc2Stats",
      },
      tc2DocsLabel: {
        id: "tc2DocsLabel",
        type: "Label",
        props: { text: "Documents retrieved:" },
        children: undefined,
        parentKey: "tc2Stats",
      },
      tc2DocsVal: {
        id: "tc2DocsVal",
        type: "Number",
        props: {
          value: { path: "/toolCalls/1/documentsRetrieved" },
          size: "sm",
        },
        children: undefined,
        parentKey: "tc2Stats",
      },
      tc2Indexes: {
        id: "tc2Indexes",
        type: "Code",
        props: {
          content: { path: "/toolCalls/1/indexes" },
          language: null,
          label: "Indexes",
          showLineNumbers: false,
          showCopy: false,
        },
        children: undefined,
        parentKey: "toolCall2",
      },
      // Tool Call 3: check_query_type
      toolCall3: {
        id: "toolCall3",
        type: "Level2Container",
        props: {
          summary: { path: "/toolCalls/2/summary" },
          defaultOpen: false,
          icon: "tool",
          status: "success",
          duration: { path: "/toolCalls/2/duration" },
        },
        children: ["tc3Content"],
        parentKey: "toolCallsWrapper",
      },
      tc3Content: {
        id: "tc3Content",
        type: "Text",
        props: { value: { path: "/toolCalls/2/description" }, variant: "body" },
        children: undefined,
        parentKey: "toolCall3",
      },
      // Tool Call 4: Answer generation
      toolCall4: {
        id: "toolCall4",
        type: "Level2Container",
        props: {
          summary: { path: "/toolCalls/3/summary" },
          defaultOpen: false,
          icon: "generation",
          status: "success",
          duration: { path: "/toolCalls/3/duration" },
        },
        children: ["tc4Content"],
        parentKey: "toolCallsWrapper",
      },
      tc4Content: {
        id: "tc4Content",
        type: "Text",
        props: { value: { path: "/toolCalls/3/description" }, variant: "body" },
        children: undefined,
        parentKey: "toolCall4",
      },
    },
    meta: {
      name: "Trace with Tool Calls",
      description: "Complete trace with nested tool call spans",
    },
  } satisfies ViewTree,
  sourceData: {
    timestamp: "Jan 12, 2026 · 14:37:21 UTC",
    input: "Can you give me a link to educational support contact",
    output:
      "Good catch — you can fix this by updating the name on your Autodesk account and then resetting the verification. Here are the steps you can follow to resolve this issue...",
    traceId: "trace-abc123",
    toolCalls: [
      {
        summary: "generate_standalone_question",
        duration: "0.01s",
        spanId: "span-001",
        model: "gpt-5-mini",
        modelDuration: "42 ms",
        inputJson: JSON.stringify(
          { question: "can you give me a link...", locale: "en_US" },
          null,
          2,
        ),
        outputJson: JSON.stringify(
          { question_first_language: "Can you provide..." },
          null,
          2,
        ),
      },
      {
        summary: "Retrieve",
        duration: "0.01s",
        indexesQueried: 2,
        documentsRetrieved: 0,
        indexes:
          "Support_Localization_extQwen3_V1\nBroadQA_Localization_extQwen3_V2",
      },
      {
        summary: "check_query_type",
        duration: "0.01s",
        description: "Query type classification completed",
      },
      {
        summary: "Answer generation",
        duration: "0.01s",
        description: "Final answer generated from retrieved context",
      },
    ],
  } satisfies SourceData,
};

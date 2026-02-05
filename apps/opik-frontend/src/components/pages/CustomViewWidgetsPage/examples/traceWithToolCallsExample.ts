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
 * L2 containers can only have leaf widgets (no InlineRow).
 * Stats use bullet-separated format. Values use sentence capitalization.
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
          "toolCallsWrapper",
          "traceLink",
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
        props: { title: "Tool calls (3)" },
        children: ["toolCall1", "toolCall2", "toolCall3"],
        parentKey: "root",
      },
      // Tool Call 1: Retrieve
      toolCall1: {
        id: "toolCall1",
        type: "Level2Container",
        props: {
          summary: { path: "/toolCalls/0/summary" },
          defaultOpen: false,
          icon: "retrieval",
          status: "success",
          duration: { path: "/toolCalls/0/duration" },
        },
        children: ["tc1Stats", "tc1Indexes"],
        parentKey: "toolCallsWrapper",
      },
      tc1Stats: {
        id: "tc1Stats",
        type: "Text",
        props: {
          value: { path: "/toolCalls/0/statsLine" },
          variant: "body-small",
        },
        children: undefined,
        parentKey: "toolCall1",
      },
      tc1Indexes: {
        id: "tc1Indexes",
        type: "Code",
        props: {
          content: { path: "/toolCalls/0/indexes" },
          language: null,
          label: "Indexes",
          showCopy: false,
        },
        children: undefined,
        parentKey: "toolCall1",
      },
      // Tool Call 2: check_query_type
      toolCall2: {
        id: "toolCall2",
        type: "Level2Container",
        props: {
          summary: { path: "/toolCalls/1/summary" },
          defaultOpen: false,
          icon: "tool",
          status: "success",
          duration: { path: "/toolCalls/1/duration" },
        },
        children: ["tc2Content"],
        parentKey: "toolCallsWrapper",
      },
      tc2Content: {
        id: "tc2Content",
        type: "Text",
        props: { value: { path: "/toolCalls/1/description" }, variant: "body-small" },
        children: undefined,
        parentKey: "toolCall2",
      },
      // Tool Call 3: Answer generation
      toolCall3: {
        id: "toolCall3",
        type: "Level2Container",
        props: {
          summary: { path: "/toolCalls/2/summary" },
          defaultOpen: false,
          icon: "generation",
          status: "success",
          duration: { path: "/toolCalls/2/duration" },
        },
        children: ["tc3Content"],
        parentKey: "toolCallsWrapper",
      },
      tc3Content: {
        id: "tc3Content",
        type: "Text",
        props: { value: { path: "/toolCalls/2/description" }, variant: "body-small" },
        children: undefined,
        parentKey: "toolCall3",
      },
    },
    meta: {
      name: "Trace with Tool Calls",
      description: "Complete trace with nested tool call spans",
    },
  } satisfies ViewTree,
  sourceData: {
    timestamp: "Jan 12, 2026 • 02:37:21 UTC",
    input: "Can you give me a link to educational support contact",
    output:
      "Good catch — you can fix this by updating the name on your Autodesk account and then resetting the verification. Here are the steps you can follow to resolve this issue...",
    traceId: "trace-abc123",
    toolCalls: [
      {
        summary: "Retrieve",
        duration: "0.01s",
        statsLine: "Indexes Queried: 2 • Documents Retrieved: 0",
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

import type { ViewTree, SourceData } from "@/lib/data-view/core/types";

/**
 * THREAD VIEW: Thread Overview with Chat
 *
 * Based on Figma node 364:18296
 * Shows a thread overview with:
 * - Single meta line with all info combined (turns • date • duration • status • cost)
 * - Chat messages as direct children (user/assistant ChatMessage widgets)
 * - Tags (Agent, Category) with labels inside chips
 * - Trace details section (L1 container with Code block)
 *
 * Note: Uses Container at root level. Thread views use ChatMessage widgets
 * instead of Input/Output TextBlocks for displaying conversation turns.
 */
export const threadOverviewExample = {
  title: "Thread Overview with Chat",
  tree: {
    version: 1,
    root: "root",
    nodes: {
      root: {
        id: "root",
        type: "Container",
        props: { layout: "stack", gap: "md", padding: "none" },
        children: [
          "metaLine",
          "userMessage",
          "assistantMessage",
          "tagsSection",
          "traceDetails",
        ],
        parentKey: null,
      },
      // Meta line: single text with all info combined
      metaLine: {
        id: "metaLine",
        type: "Text",
        props: {
          value: { path: "/threadInfo/metaLine" },
          variant: "caption",
        },
        children: undefined,
        parentKey: "root",
      },
      // Chat messages as direct children (no wrapper container)
      userMessage: {
        id: "userMessage",
        type: "ChatMessage",
        props: {
          content: { path: "/turn/userMessage" },
          role: "user",
        },
        children: undefined,
        parentKey: "root",
      },
      assistantMessage: {
        id: "assistantMessage",
        type: "ChatMessage",
        props: {
          content: { path: "/turn/assistantMessage" },
          role: "assistant",
        },
        children: undefined,
        parentKey: "root",
      },
      // Tags Section - labels included inside chips, all grey (default variant)
      tagsSection: {
        id: "tagsSection",
        type: "InlineRow",
        props: {},
        children: ["agentTag", "categoryTag"],
        parentKey: "root",
      },
      agentTag: {
        id: "agentTag",
        type: "Tag",
        props: {
          label: { path: "/turn/agentTagLabel" },
          variant: "default",
        },
        children: undefined,
        parentKey: "tagsSection",
      },
      categoryTag: {
        id: "categoryTag",
        type: "Tag",
        props: {
          label: { path: "/turn/categoryTagLabel" },
          variant: "default",
        },
        children: undefined,
        parentKey: "tagsSection",
      },
      // Trace Details - L1 container with single Code block
      traceDetails: {
        id: "traceDetails",
        type: "Level1Container",
        props: {
          title: "Trace details",
          collapsible: true,
          defaultOpen: false,
        },
        children: ["traceCode"],
        parentKey: "root",
      },
      traceCode: {
        id: "traceCode",
        type: "Code",
        props: {
          code: { path: "/turn/traceDetailsCode" },
          language: "text",
        },
        children: undefined,
        parentKey: "traceDetails",
      },
    },
    meta: {
      name: "Thread Overview",
      description: "Thread with chat turns and metadata",
    },
  } satisfies ViewTree,
  sourceData: {
    threadInfo: {
      metaLine:
        "Turns:26 • Nov 25, 2025 • 14.5s • Inactive • Total cost: $0.0024",
    },
    turn: {
      userMessage: "Can you recommend a good restaurant for my client dinner?",
      assistantMessage:
        "Yes, you can monitor failed API calls using Opik's built-in monitoring features. Here's how to set it up:\n\n1. Navigate to your project's Monitoring tab\n2. Create a new alert rule with type \"API Error Rate\"\n3. Set your threshold (e.g., alert when error rate > 5%)\n4. Configure notification channels (email, Slack, or webhook)",
      agentTagLabel: "Agent: fallback",
      categoryTagLabel: "Category: other",
      traceDetailsCode:
        "Trace ID: 019abbdc-1e96-7f8a-b2c3-d4e5f6a7b8c9\nDuration: 14.5s\nPipeline: classify → fallback_tool → format_response",
    },
  } satisfies SourceData,
};

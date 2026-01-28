import type { ViewTree, SourceData } from "@/lib/data-view/core/types";

/**
 * THREAD VIEW: Thread Overview with Chat
 *
 * Based on Figma node 245-19693
 * Shows a thread overview with:
 * - Thread info header (turns, date, duration, status, cost)
 * - Chat turn with user/assistant messages (ChatMessage widgets)
 * - Tags (Agent, Category)
 * - Trace details section
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
          "threadHeader",
          "threadMeta",
          "divider1",
          "chatSection",
          "tagsSection",
          "traceDetails",
        ],
        parentKey: null,
      },
      // Thread Header
      threadHeader: {
        id: "threadHeader",
        type: "Header",
        props: {
          text: { path: "/threadInfo/title" },
          level: 2,
        },
        children: undefined,
        parentKey: "root",
      },
      threadMeta: {
        id: "threadMeta",
        type: "InlineRow",
        props: {},
        children: [
          "turnsLabel",
          "turnsValue",
          "dateValue",
          "durationValue",
          "statusTag",
          "costLabel",
          "costValue",
        ],
        parentKey: "root",
      },
      turnsLabel: {
        id: "turnsLabel",
        type: "Label",
        props: { text: "Turns:" },
        children: undefined,
        parentKey: "threadMeta",
      },
      turnsValue: {
        id: "turnsValue",
        type: "Number",
        props: { value: { path: "/threadInfo/turns" }, size: "sm" },
        children: undefined,
        parentKey: "threadMeta",
      },
      dateValue: {
        id: "dateValue",
        type: "Text",
        props: { value: { path: "/threadInfo/date" }, variant: "caption" },
        children: undefined,
        parentKey: "threadMeta",
      },
      durationValue: {
        id: "durationValue",
        type: "Text",
        props: { value: { path: "/threadInfo/duration" }, variant: "caption" },
        children: undefined,
        parentKey: "threadMeta",
      },
      statusTag: {
        id: "statusTag",
        type: "Tag",
        props: {
          label: { path: "/threadInfo/status" },
          variant: "default",
        },
        children: undefined,
        parentKey: "threadMeta",
      },
      costLabel: {
        id: "costLabel",
        type: "Label",
        props: { text: "Total cost:" },
        children: undefined,
        parentKey: "threadMeta",
      },
      costValue: {
        id: "costValue",
        type: "Text",
        props: { value: { path: "/threadInfo/totalCost" }, variant: "body" },
        children: undefined,
        parentKey: "threadMeta",
      },
      divider1: {
        id: "divider1",
        type: "Divider",
        props: {},
        children: undefined,
        parentKey: "root",
      },
      // Chat Section
      chatSection: {
        id: "chatSection",
        type: "Level1Container",
        props: { title: "Conversation" },
        children: ["userMessage", "assistantMessage"],
        parentKey: "root",
      },
      userMessage: {
        id: "userMessage",
        type: "ChatMessage",
        props: {
          content: { path: "/turn/userMessage" },
          role: "user",
        },
        children: undefined,
        parentKey: "chatSection",
      },
      assistantMessage: {
        id: "assistantMessage",
        type: "ChatMessage",
        props: {
          content: { path: "/turn/assistantMessage" },
          role: "assistant",
        },
        children: undefined,
        parentKey: "chatSection",
      },
      // Tags Section
      tagsSection: {
        id: "tagsSection",
        type: "InlineRow",
        props: {},
        children: ["agentLabel", "agentTag", "categoryLabel", "categoryTag"],
        parentKey: "root",
      },
      agentLabel: {
        id: "agentLabel",
        type: "Label",
        props: { text: "Agent:" },
        children: undefined,
        parentKey: "tagsSection",
      },
      agentTag: {
        id: "agentTag",
        type: "Tag",
        props: {
          label: { path: "/turn/agent" },
          variant: "info",
        },
        children: undefined,
        parentKey: "tagsSection",
      },
      categoryLabel: {
        id: "categoryLabel",
        type: "Label",
        props: { text: "Category:" },
        children: undefined,
        parentKey: "tagsSection",
      },
      categoryTag: {
        id: "categoryTag",
        type: "Tag",
        props: {
          label: { path: "/turn/category" },
          variant: "default",
        },
        children: undefined,
        parentKey: "tagsSection",
      },
      // Trace Details
      traceDetails: {
        id: "traceDetails",
        type: "Level2Container",
        props: {
          summary: "Trace details",
          defaultOpen: false,
        },
        children: ["traceMetaRow", "pipelineSection"],
        parentKey: "root",
      },
      traceMetaRow: {
        id: "traceMetaRow",
        type: "InlineRow",
        props: {},
        children: [
          "traceIdLabel",
          "traceIdValue",
          "traceDurLabel",
          "traceDurValue",
        ],
        parentKey: "traceDetails",
      },
      traceIdLabel: {
        id: "traceIdLabel",
        type: "Label",
        props: { text: "Trace ID:" },
        children: undefined,
        parentKey: "traceMetaRow",
      },
      traceIdValue: {
        id: "traceIdValue",
        type: "Text",
        props: {
          value: { path: "/turn/traceId" },
          variant: "body",
          monospace: true,
        },
        children: undefined,
        parentKey: "traceMetaRow",
      },
      traceDurLabel: {
        id: "traceDurLabel",
        type: "Label",
        props: { text: "Duration:" },
        children: undefined,
        parentKey: "traceMetaRow",
      },
      traceDurValue: {
        id: "traceDurValue",
        type: "Text",
        props: { value: { path: "/turn/duration" }, variant: "body" },
        children: undefined,
        parentKey: "traceMetaRow",
      },
      pipelineSection: {
        id: "pipelineSection",
        type: "InlineRow",
        props: {},
        children: ["pipelineLabel", "pipelineValue"],
        parentKey: "traceDetails",
      },
      pipelineLabel: {
        id: "pipelineLabel",
        type: "Label",
        props: { text: "Pipeline:" },
        children: undefined,
        parentKey: "pipelineSection",
      },
      pipelineValue: {
        id: "pipelineValue",
        type: "Text",
        props: {
          value: { path: "/turn/pipeline" },
          variant: "caption",
          monospace: true,
        },
        children: undefined,
        parentKey: "pipelineSection",
      },
    },
    meta: {
      name: "Thread Overview",
      description: "Thread with chat turns and metadata",
    },
  } satisfies ViewTree,
  sourceData: {
    threadInfo: {
      title: "Thread · 26 turns",
      turns: 26,
      date: "Nov 25, 2025",
      duration: "14.5s",
      status: "inactive",
      totalCost: "$0.00249",
    },
    turn: {
      userMessage: "Can you recommend a good restaurant for my client dinner?",
      assistantMessage:
        "Thank you for your inquiry! While I'm unable to recommend specific restaurants, I can suggest some approaches:\n\n1. Check local review sites for highly-rated business dining options\n2. Consider the dietary preferences of your clients\n3. Look for restaurants with private dining rooms for confidential discussions\n4. Ask colleagues for personal recommendations in the area",
      agent: "fallback",
      category: "other",
      traceId: "019abbdc-1e96-7f8a-b2c3-d4e5f6a7b8c9",
      pipeline: "classify → fallback_tool → format_response",
      tool: "fallback",
      duration: "14.5s",
    },
  } satisfies SourceData,
};

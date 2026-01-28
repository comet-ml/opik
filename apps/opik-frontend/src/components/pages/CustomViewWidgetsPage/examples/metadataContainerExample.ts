import type { ViewTree, SourceData } from "@/lib/data-view/core/types";

/**
 * PATTERN DEMO: Metadata Container
 *
 * Demonstrates Level1Container with various metadata fields:
 * - Model information
 * - Performance metrics
 * - Tags for status/classification
 * - Links to related resources
 *
 * Note: This is a pattern demo showing widget composition, not a complete trace view.
 */
export const metadataContainerExample = {
  title: "Metadata Container",
  tree: {
    version: 1,
    root: "root",
    nodes: {
      root: {
        id: "root",
        type: "Level1Container",
        props: { title: "Run Metadata" },
        children: ["modelRow", "performanceRow", "tagsRow", "linksRow"],
        parentKey: null,
      },
      // Model Information Row
      modelRow: {
        id: "modelRow",
        type: "InlineRow",
        props: {},
        children: [
          "modelLabel",
          "modelValue",
          "providerLabel",
          "providerValue",
        ],
        parentKey: "root",
      },
      modelLabel: {
        id: "modelLabel",
        type: "Label",
        props: { text: "Model:" },
        children: undefined,
        parentKey: "modelRow",
      },
      modelValue: {
        id: "modelValue",
        type: "Text",
        props: { value: { path: "/model" }, variant: "bold" },
        children: undefined,
        parentKey: "modelRow",
      },
      providerLabel: {
        id: "providerLabel",
        type: "Label",
        props: { text: "Provider:" },
        children: undefined,
        parentKey: "modelRow",
      },
      providerValue: {
        id: "providerValue",
        type: "Text",
        props: { value: { path: "/provider" }, variant: "body" },
        children: undefined,
        parentKey: "modelRow",
      },
      // Performance Row
      performanceRow: {
        id: "performanceRow",
        type: "InlineRow",
        props: {},
        children: [
          "latencyLabel",
          "latencyValue",
          "tokensLabel",
          "tokensValue",
          "costLabel",
          "costValue",
        ],
        parentKey: "root",
      },
      latencyLabel: {
        id: "latencyLabel",
        type: "Label",
        props: { text: "Latency:" },
        children: undefined,
        parentKey: "performanceRow",
      },
      latencyValue: {
        id: "latencyValue",
        type: "Text",
        props: { value: { path: "/latency" }, variant: "body" },
        children: undefined,
        parentKey: "performanceRow",
      },
      tokensLabel: {
        id: "tokensLabel",
        type: "Label",
        props: { text: "Tokens:" },
        children: undefined,
        parentKey: "performanceRow",
      },
      tokensValue: {
        id: "tokensValue",
        type: "Number",
        props: { value: { path: "/totalTokens" }, size: "sm" },
        children: undefined,
        parentKey: "performanceRow",
      },
      costLabel: {
        id: "costLabel",
        type: "Label",
        props: { text: "Cost:" },
        children: undefined,
        parentKey: "performanceRow",
      },
      costValue: {
        id: "costValue",
        type: "Text",
        props: { value: { path: "/cost" }, variant: "body" },
        children: undefined,
        parentKey: "performanceRow",
      },
      // Tags Row
      tagsRow: {
        id: "tagsRow",
        type: "InlineRow",
        props: {},
        children: ["statusTag", "cacheTag", "streamTag"],
        parentKey: "root",
      },
      statusTag: {
        id: "statusTag",
        type: "Tag",
        props: {
          label: { path: "/status" },
          variant: "success",
        },
        children: undefined,
        parentKey: "tagsRow",
      },
      cacheTag: {
        id: "cacheTag",
        type: "Tag",
        props: {
          label: "cached",
          variant: "info",
        },
        children: undefined,
        parentKey: "tagsRow",
      },
      streamTag: {
        id: "streamTag",
        type: "Tag",
        props: {
          label: "streamed",
          variant: "default",
        },
        children: undefined,
        parentKey: "tagsRow",
      },
      // Links Row
      linksRow: {
        id: "linksRow",
        type: "InlineRow",
        props: {},
        children: ["traceLink", "spanLink"],
        parentKey: "root",
      },
      traceLink: {
        id: "traceLink",
        type: "LinkButton",
        props: {
          type: "trace",
          id: { path: "/traceId" },
          label: "View Trace",
        },
        children: undefined,
        parentKey: "linksRow",
      },
      spanLink: {
        id: "spanLink",
        type: "LinkButton",
        props: {
          type: "span",
          id: { path: "/spanId" },
          label: "View Span",
        },
        children: undefined,
        parentKey: "linksRow",
      },
    },
    meta: {
      name: "Metadata Container",
      description: "Run metadata with tags and navigation links",
    },
  } satisfies ViewTree,
  sourceData: {
    model: "gpt-4-turbo",
    provider: "OpenAI",
    latency: "1.23s",
    totalTokens: 1847,
    cost: "$0.0124",
    status: "completed",
    traceId: "trace-meta-001",
    spanId: "span-meta-001",
  } satisfies SourceData,
};

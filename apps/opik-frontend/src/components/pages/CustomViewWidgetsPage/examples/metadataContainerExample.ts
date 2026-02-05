import type { ViewTree, SourceData } from "@/lib/data-view/core/types";

/**
 * PATTERN DEMO: Metadata Container with Multi-Path Bindings
 *
 * Demonstrates Level1Container with separate widgets bound to individual data paths
 * (Figma node 364:19811, 364:19891):
 * - InlineRow with muted background for stats (individual Text widgets bound to paths)
 * - InlineRow for tags (horizontal layout)
 * - InlineRow for navigation links (horizontal layout)
 *
 * L1 containers use 16px gap between children.
 * This example shows realistic view generation with individual data bindings.
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
        props: { title: "Metadata", collapsible: true },
        children: ["statsRow", "tagsRow", "linksRow"],
        parentKey: null,
      },
      // Stats in horizontal row with muted background (matches Figma 364:19891)
      statsRow: {
        id: "statsRow",
        type: "InlineRow",
        props: { background: "muted" },
        children: [
          "modelLabel",
          "modelValue",
          "sep1",
          "providerLabel",
          "providerValue",
          "sep2",
          "latencyLabel",
          "latencyValue",
          "sep3",
          "tokensLabel",
          "tokensValue",
          "sep4",
          "costLabel",
          "costValue",
        ],
        parentKey: "root",
      },
      modelLabel: {
        id: "modelLabel",
        type: "Text",
        props: { value: "Model:" },
        children: undefined,
        parentKey: "statsRow",
      },
      modelValue: {
        id: "modelValue",
        type: "Text",
        props: { value: { path: "/model" } },
        children: undefined,
        parentKey: "statsRow",
      },
      sep1: {
        id: "sep1",
        type: "Text",
        props: { value: "•" },
        children: undefined,
        parentKey: "statsRow",
      },
      providerLabel: {
        id: "providerLabel",
        type: "Text",
        props: { value: "Provider:" },
        children: undefined,
        parentKey: "statsRow",
      },
      providerValue: {
        id: "providerValue",
        type: "Text",
        props: { value: { path: "/provider" } },
        children: undefined,
        parentKey: "statsRow",
      },
      sep2: {
        id: "sep2",
        type: "Text",
        props: { value: "•" },
        children: undefined,
        parentKey: "statsRow",
      },
      latencyLabel: {
        id: "latencyLabel",
        type: "Text",
        props: { value: "Latency:" },
        children: undefined,
        parentKey: "statsRow",
      },
      latencyValue: {
        id: "latencyValue",
        type: "Text",
        props: { value: { path: "/latency" } },
        children: undefined,
        parentKey: "statsRow",
      },
      sep3: {
        id: "sep3",
        type: "Text",
        props: { value: "•" },
        children: undefined,
        parentKey: "statsRow",
      },
      tokensLabel: {
        id: "tokensLabel",
        type: "Text",
        props: { value: "Tokens:" },
        children: undefined,
        parentKey: "statsRow",
      },
      tokensValue: {
        id: "tokensValue",
        type: "Text",
        props: { value: { path: "/tokens" } },
        children: undefined,
        parentKey: "statsRow",
      },
      sep4: {
        id: "sep4",
        type: "Text",
        props: { value: "•" },
        children: undefined,
        parentKey: "statsRow",
      },
      costLabel: {
        id: "costLabel",
        type: "Text",
        props: { value: "Cost:" },
        children: undefined,
        parentKey: "statsRow",
      },
      costValue: {
        id: "costValue",
        type: "Text",
        props: { value: { path: "/cost" } },
        children: undefined,
        parentKey: "statsRow",
      },
      // Tags in horizontal row
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
          label: "Cached",
          variant: "default",
        },
        children: undefined,
        parentKey: "tagsRow",
      },
      streamTag: {
        id: "streamTag",
        type: "Tag",
        props: {
          label: "Streamed",
          variant: "default",
        },
        children: undefined,
        parentKey: "tagsRow",
      },
      // Links in horizontal row
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
          label: "Trace",
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
          label: "Span",
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
    tokens: "1847",
    cost: "$0.0124",
    status: "Completed",
    traceId: "trace-meta-001",
    spanId: "span-meta-001",
  } satisfies SourceData,
};

import type { ViewTree, SourceData } from "@/lib/data-view/core/types";

/**
 * PATTERN DEMO: Inline Row Groupings
 *
 * Demonstrates InlineRow usage patterns:
 * - Tags with different variants
 * - Labels with values
 * - Links and navigation
 * - Mixed widget compositions
 *
 * Note: This is a pattern demo showing InlineRow composition, not a complete trace view.
 */
export const inlineGroupingExample = {
  title: "Inline Row Groupings",
  tree: {
    version: 1,
    root: "root",
    nodes: {
      root: {
        id: "root",
        type: "Level1Container",
        props: { title: "InlineRow Examples" },
        children: ["statusRow", "metricsRow", "tagsRow", "linksRow"],
        parentKey: null,
      },
      // Status Row: Labels + Tags
      statusRow: {
        id: "statusRow",
        type: "InlineRow",
        props: {},
        children: [
          "statusLabel",
          "statusTag",
          "severityLabel",
          "severityTag",
          "priorityLabel",
          "priorityTag",
        ],
        parentKey: "root",
      },
      statusLabel: {
        id: "statusLabel",
        type: "Label",
        props: { text: "Status:" },
        children: undefined,
        parentKey: "statusRow",
      },
      statusTag: {
        id: "statusTag",
        type: "Tag",
        props: {
          label: { path: "/status" },
          variant: "success",
        },
        children: undefined,
        parentKey: "statusRow",
      },
      severityLabel: {
        id: "severityLabel",
        type: "Label",
        props: { text: "Severity:" },
        children: undefined,
        parentKey: "statusRow",
      },
      severityTag: {
        id: "severityTag",
        type: "Tag",
        props: {
          label: { path: "/severity" },
          variant: "warning",
        },
        children: undefined,
        parentKey: "statusRow",
      },
      priorityLabel: {
        id: "priorityLabel",
        type: "Label",
        props: { text: "Priority:" },
        children: undefined,
        parentKey: "statusRow",
      },
      priorityTag: {
        id: "priorityTag",
        type: "Tag",
        props: {
          label: { path: "/priority" },
          variant: "error",
        },
        children: undefined,
        parentKey: "statusRow",
      },
      // Metrics Row: Labels + Numbers
      metricsRow: {
        id: "metricsRow",
        type: "InlineRow",
        props: {},
        children: [
          "inputTokensLabel",
          "inputTokensValue",
          "outputTokensLabel",
          "outputTokensValue",
          "totalCostLabel",
          "totalCostValue",
        ],
        parentKey: "root",
      },
      inputTokensLabel: {
        id: "inputTokensLabel",
        type: "Label",
        props: { text: "Input tokens:" },
        children: undefined,
        parentKey: "metricsRow",
      },
      inputTokensValue: {
        id: "inputTokensValue",
        type: "Number",
        props: { value: { path: "/inputTokens" }, size: "sm" },
        children: undefined,
        parentKey: "metricsRow",
      },
      outputTokensLabel: {
        id: "outputTokensLabel",
        type: "Label",
        props: { text: "Output tokens:" },
        children: undefined,
        parentKey: "metricsRow",
      },
      outputTokensValue: {
        id: "outputTokensValue",
        type: "Number",
        props: { value: { path: "/outputTokens" }, size: "sm" },
        children: undefined,
        parentKey: "metricsRow",
      },
      totalCostLabel: {
        id: "totalCostLabel",
        type: "Label",
        props: { text: "Cost:" },
        children: undefined,
        parentKey: "metricsRow",
      },
      totalCostValue: {
        id: "totalCostValue",
        type: "Text",
        props: { value: { path: "/cost" }, variant: "bold" },
        children: undefined,
        parentKey: "metricsRow",
      },
      // Tags Row: Multiple Tags
      tagsRow: {
        id: "tagsRow",
        type: "InlineRow",
        props: {},
        children: ["tagsLabel", "tag1", "tag2", "tag3", "tag4", "tag5"],
        parentKey: "root",
      },
      tagsLabel: {
        id: "tagsLabel",
        type: "Label",
        props: { text: "Tags:" },
        children: undefined,
        parentKey: "tagsRow",
      },
      tag1: {
        id: "tag1",
        type: "Tag",
        props: { label: "retrieval", variant: "info" },
        children: undefined,
        parentKey: "tagsRow",
      },
      tag2: {
        id: "tag2",
        type: "Tag",
        props: { label: "tool-call", variant: "default" },
        children: undefined,
        parentKey: "tagsRow",
      },
      tag3: {
        id: "tag3",
        type: "Tag",
        props: { label: "cached", variant: "success" },
        children: undefined,
        parentKey: "tagsRow",
      },
      tag4: {
        id: "tag4",
        type: "Tag",
        props: { label: "slow", variant: "warning" },
        children: undefined,
        parentKey: "tagsRow",
      },
      tag5: {
        id: "tag5",
        type: "Tag",
        props: { label: "error", variant: "error" },
        children: undefined,
        parentKey: "tagsRow",
      },
      // Links Row: Multiple Links
      linksRow: {
        id: "linksRow",
        type: "InlineRow",
        props: {},
        children: ["linksLabel", "traceLink", "spanLink", "docsLink"],
        parentKey: "root",
      },
      linksLabel: {
        id: "linksLabel",
        type: "Label",
        props: { text: "Navigate:" },
        children: undefined,
        parentKey: "linksRow",
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
      docsLink: {
        id: "docsLink",
        type: "Link",
        props: {
          url: { path: "/docsUrl" },
          text: "Documentation",
          label: null,
        },
        children: undefined,
        parentKey: "linksRow",
      },
    },
    meta: {
      name: "Inline Row Examples",
      description: "Various InlineRow composition patterns",
    },
  } satisfies ViewTree,
  sourceData: {
    status: "completed",
    severity: "medium",
    priority: "high",
    inputTokens: 542,
    outputTokens: 1205,
    cost: "$0.0089",
    traceId: "inline-trace-001",
    spanId: "inline-span-001",
    docsUrl: "https://docs.example.com/api",
  } satisfies SourceData,
};

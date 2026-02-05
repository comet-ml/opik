import type { ViewTree, SourceData } from "@/lib/data-view/core/types";

/**
 * SPAN-LEVEL VIEW: Retrieval Span
 *
 * Based on Figma node 245-15436
 * Shows a retrieval span with:
 * - Level2Container with summary
 * - Stats line with bullet-separated format: "Indexes Queried: 2 • Documents Retrieved: 0"
 * - Code block with integrated label showing index names
 *
 * Note: This is a span-level view (Level2Container at root), designed to be
 * embedded within a trace view. Level2 at root is valid for span detail views.
 * L2 containers can only contain leaf widgets (no InlineRow, no Container).
 */
export const retrievalSpanExample = {
  title: "Retrieval Span",
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
          icon: "retrieval",
          status: "success",
          duration: { path: "/duration" },
        },
        children: ["statsLine", "indexesList"],
        parentKey: null,
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
      indexesList: {
        id: "indexesList",
        type: "Code",
        props: {
          content: { path: "/indexesList" },
          language: null,
          label: "Indexes:",
          showCopy: false,
        },
        children: undefined,
        parentKey: "root",
      },
    },
    meta: {
      name: "Retrieval Span",
      description: "Retrieval operation with index information",
    },
  } satisfies ViewTree,
  sourceData: {
    summary: "Retrieve",
    duration: "0.01s",
    statsLine: "Indexes Queried: 2 • Documents Retrieved: 0",
    indexesList:
      "Support_Localization_extQwen3_V1\nBroadQA_Localization_extQwen3_V2",
  } satisfies SourceData,
};

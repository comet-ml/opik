import type { ViewTree, SourceData } from "@/lib/data-view/core/types";

/**
 * SPAN-LEVEL VIEW: Retrieval Span
 *
 * Based on Figma node 245-15436
 * Shows a retrieval span with:
 * - Level2Container with summary
 * - Indexes queried count
 * - Documents retrieved count
 * - List of index names
 *
 * Note: This is a span-level view (Level2Container at root), designed to be
 * embedded within a trace view. Level2 at root is valid for span detail views.
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
          defaultOpen: true,
          icon: "retrieval",
          status: "success",
          duration: { path: "/duration" },
        },
        children: ["statsRow", "indexesLabel", "indexesList"],
        parentKey: null,
      },
      statsRow: {
        id: "statsRow",
        type: "InlineRow",
        props: {},
        children: [
          "indexesQueriedLabel",
          "indexesQueriedValue",
          "docsRetrievedLabel",
          "docsRetrievedValue",
        ],
        parentKey: "root",
      },
      indexesQueriedLabel: {
        id: "indexesQueriedLabel",
        type: "Label",
        props: { text: "Indexes queried:" },
        children: undefined,
        parentKey: "statsRow",
      },
      indexesQueriedValue: {
        id: "indexesQueriedValue",
        type: "Number",
        props: {
          value: { path: "/indexesQueried" },
          size: "sm",
        },
        children: undefined,
        parentKey: "statsRow",
      },
      docsRetrievedLabel: {
        id: "docsRetrievedLabel",
        type: "Label",
        props: { text: "Documents retrieved:" },
        children: undefined,
        parentKey: "statsRow",
      },
      docsRetrievedValue: {
        id: "docsRetrievedValue",
        type: "Number",
        props: {
          value: { path: "/documentsRetrieved" },
          size: "sm",
        },
        children: undefined,
        parentKey: "statsRow",
      },
      indexesLabel: {
        id: "indexesLabel",
        type: "Label",
        props: { text: "Indexes:" },
        children: undefined,
        parentKey: "root",
      },
      indexesList: {
        id: "indexesList",
        type: "Code",
        props: {
          content: { path: "/indexesList" },
          language: null,
          label: null,
          showLineNumbers: false,
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
    indexesQueried: 2,
    documentsRetrieved: 0,
    indexesList:
      "Support_Localization_extQwen3_V1\nBroadQA_Localization_extQwen3_V2",
  } satisfies SourceData,
};

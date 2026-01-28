import type { ViewTree, SourceData } from "@/lib/data-view/core/types";

/**
 * PATTERN DEMO: Container Layouts
 *
 * Demonstrates the Container widget with different layout modes:
 * - grid-2: Two-column grid layout
 * - stack: Vertical stacking (default)
 * - row: Horizontal row with wrapping
 *
 * Note: This is a pattern demo showing Container layout options, not a complete trace view.
 */
export const containerLayoutExample = {
  title: "Container Layouts",
  tree: {
    version: 1,
    root: "root",
    nodes: {
      root: {
        id: "root",
        type: "Container",
        props: { layout: "stack", gap: "lg", padding: "md" },
        children: ["header", "gridSection", "rowSection"],
        parentKey: null,
      },
      // Header
      header: {
        id: "header",
        type: "Header",
        props: { text: "Container Layout Examples", level: 1 },
        children: undefined,
        parentKey: "root",
      },
      // Grid-2 Section
      gridSection: {
        id: "gridSection",
        type: "Level1Container",
        props: { title: "Grid Layout (2 columns)" },
        children: ["gridContainer"],
        parentKey: "root",
      },
      gridContainer: {
        id: "gridContainer",
        type: "Container",
        props: { layout: "grid-2", gap: "md" },
        children: ["fund1", "fund2", "fund3", "fund4"],
        parentKey: "gridSection",
      },
      fund1: {
        id: "fund1",
        type: "Level1Container",
        props: { title: null },
        children: ["fund1Name", "fund1Value"],
        parentKey: "gridContainer",
      },
      fund1Name: {
        id: "fund1Name",
        type: "Label",
        props: { text: "Growth Fund" },
        children: undefined,
        parentKey: "fund1",
      },
      fund1Value: {
        id: "fund1Value",
        type: "Text",
        props: { value: { path: "/funds/0/value" }, variant: "body" },
        children: undefined,
        parentKey: "fund1",
      },
      fund2: {
        id: "fund2",
        type: "Level1Container",
        props: { title: null },
        children: ["fund2Name", "fund2Value"],
        parentKey: "gridContainer",
      },
      fund2Name: {
        id: "fund2Name",
        type: "Label",
        props: { text: "Income Fund" },
        children: undefined,
        parentKey: "fund2",
      },
      fund2Value: {
        id: "fund2Value",
        type: "Text",
        props: { value: { path: "/funds/1/value" }, variant: "body" },
        children: undefined,
        parentKey: "fund2",
      },
      fund3: {
        id: "fund3",
        type: "Level1Container",
        props: { title: null },
        children: ["fund3Name", "fund3Value"],
        parentKey: "gridContainer",
      },
      fund3Name: {
        id: "fund3Name",
        type: "Label",
        props: { text: "Bond Fund" },
        children: undefined,
        parentKey: "fund3",
      },
      fund3Value: {
        id: "fund3Value",
        type: "Text",
        props: { value: { path: "/funds/2/value" }, variant: "body" },
        children: undefined,
        parentKey: "fund3",
      },
      fund4: {
        id: "fund4",
        type: "Level1Container",
        props: { title: null },
        children: ["fund4Name", "fund4Value"],
        parentKey: "gridContainer",
      },
      fund4Name: {
        id: "fund4Name",
        type: "Label",
        props: { text: "Index Fund" },
        children: undefined,
        parentKey: "fund4",
      },
      fund4Value: {
        id: "fund4Value",
        type: "Text",
        props: { value: { path: "/funds/3/value" }, variant: "body" },
        children: undefined,
        parentKey: "fund4",
      },
      // Row Section
      rowSection: {
        id: "rowSection",
        type: "Level1Container",
        props: { title: "Row Layout (horizontal with wrap)" },
        children: ["rowContainer"],
        parentKey: "root",
      },
      rowContainer: {
        id: "rowContainer",
        type: "Container",
        props: { layout: "row", gap: "sm" },
        children: ["tag1", "tag2", "tag3", "tag4", "tag5"],
        parentKey: "rowSection",
      },
      tag1: {
        id: "tag1",
        type: "Tag",
        props: { label: "Finance", variant: "info" },
        children: undefined,
        parentKey: "rowContainer",
      },
      tag2: {
        id: "tag2",
        type: "Tag",
        props: { label: "Investment", variant: "success" },
        children: undefined,
        parentKey: "rowContainer",
      },
      tag3: {
        id: "tag3",
        type: "Tag",
        props: { label: "Portfolio", variant: "default" },
        children: undefined,
        parentKey: "rowContainer",
      },
      tag4: {
        id: "tag4",
        type: "Tag",
        props: { label: "Analysis", variant: "warning" },
        children: undefined,
        parentKey: "rowContainer",
      },
      tag5: {
        id: "tag5",
        type: "Tag",
        props: { label: "Report", variant: "info" },
        children: undefined,
        parentKey: "rowContainer",
      },
    },
    meta: {
      name: "Container Layouts",
      description: "Demonstrates Container widget layout options",
    },
  } satisfies ViewTree,
  sourceData: {
    funds: [
      { name: "Growth Fund", value: "$12,450.00" },
      { name: "Income Fund", value: "$8,320.50" },
      { name: "Bond Fund", value: "$5,100.00" },
      { name: "Index Fund", value: "$15,780.25" },
    ],
  } satisfies SourceData,
};

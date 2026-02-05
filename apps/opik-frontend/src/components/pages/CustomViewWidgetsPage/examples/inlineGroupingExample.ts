import type { ViewTree, SourceData } from "@/lib/data-view/core/types";

/**
 * PATTERN DEMO: Container Layouts
 *
 * Demonstrates L2 accordion sections with container layout patterns:
 * - Stats section: muted background with bullet-separated fund values
 * - Tags section: horizontal gray tags in a row layout
 *
 * Note: L2Container sections are collapsible accordions within L1.
 * InlineRow provides horizontal layout for children.
 */
export const inlineGroupingExample = {
  title: "Container Layouts",
  tree: {
    version: 1,
    root: "root",
    nodes: {
      root: {
        id: "root",
        type: "Level1Container",
        props: { title: "Container Layouts", collapsible: true },
        children: ["statsSection", "tagsSection"],
        parentKey: null,
      },
      // Section 1: Stats with muted background
      statsSection: {
        id: "statsSection",
        type: "Level2Container",
        props: {
          summary: "No grid layouts please",
          icon: null,
          defaultOpen: true,
        },
        children: ["statsRow"],
        parentKey: "root",
      },
      statsRow: {
        id: "statsRow",
        type: "InlineRow",
        props: { background: "muted" },
        children: ["statsText"],
        parentKey: "statsSection",
      },
      statsText: {
        id: "statsText",
        type: "Text",
        props: { value: { path: "/statsLine" } },
        children: undefined,
        parentKey: "statsRow",
      },
      // Section 2: Tags row layout (horizontal with wrap)
      tagsSection: {
        id: "tagsSection",
        type: "Level2Container",
        props: {
          summary: "Row layout (horizontal with wrap)",
          icon: null,
          defaultOpen: true,
        },
        children: ["tagsRow"],
        parentKey: "root",
      },
      tagsRow: {
        id: "tagsRow",
        type: "InlineRow",
        props: {},
        children: ["tag1", "tag2", "tag3", "tag4", "tag5"],
        parentKey: "tagsSection",
      },
      tag1: {
        id: "tag1",
        type: "Tag",
        props: { label: "Finance", variant: "default" },
        children: undefined,
        parentKey: "tagsRow",
      },
      tag2: {
        id: "tag2",
        type: "Tag",
        props: { label: "Investment", variant: "default" },
        children: undefined,
        parentKey: "tagsRow",
      },
      tag3: {
        id: "tag3",
        type: "Tag",
        props: { label: "Portfolio", variant: "default" },
        children: undefined,
        parentKey: "tagsRow",
      },
      tag4: {
        id: "tag4",
        type: "Tag",
        props: { label: "Analysis", variant: "default" },
        children: undefined,
        parentKey: "tagsRow",
      },
      tag5: {
        id: "tag5",
        type: "Tag",
        props: { label: "Report", variant: "default" },
        children: undefined,
        parentKey: "tagsRow",
      },
    },
    meta: {
      name: "Container Layouts",
      description:
        "L2 accordion sections demonstrating container layout patterns",
    },
  } satisfies ViewTree,
  sourceData: {
    statsLine:
      "Growth Fund: $12,450.00 • Income Fund: $8,320.50 • Bond Fund: $5,100.00 • Index Fund: $15,780.25",
  } satisfies SourceData,
};

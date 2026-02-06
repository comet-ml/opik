import type { ViewTree, SourceData } from "@/lib/data-view/core/types";

/**
 * COMPLETE TRACE VIEW: Simple Trace
 *
 * Based on Figma node 245-14061
 * Shows a basic trace with:
 * - Timestamp header
 * - Input/Output TextBlocks at root level (Container)
 * - View trace link
 *
 * Note: Uses Container at root level per schema rules (Input/Output at root, not nested).
 */
export const simpleTraceExample = {
  title: "Simple Trace View",
  tree: {
    version: 1,
    root: "root",
    nodes: {
      root: {
        id: "root",
        type: "Container",
        props: { layout: "stack", gap: "md", padding: "none" },
        children: ["timestamp", "input", "output", "traceLink"],
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
    },
    meta: {
      name: "Simple Trace View",
      description: "Basic trace display with input/output and navigation",
    },
  } satisfies ViewTree,
  sourceData: {
    timestamp: "Jan 12, 2026 • 02:37:21 UTC",
    input: "Can you give me a link to educational support contact",
    output:
      "Good catch — you can fix this by updating the name on your Autodesk account and then resetting the verification. Here are the steps you can follow to resolve this issue:\n\n1. Go to your Autodesk Account settings\n2. Update your profile information\n3. Reset the verification process\n4. Contact support if you need additional help",
    traceId: "abc123-def456-ghi789",
  } satisfies SourceData,
};

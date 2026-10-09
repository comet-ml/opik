import { ReactNode, useRef } from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import { Dialog, DialogContent, DialogTitle } from "@/ui/dialog";
import { TRACE_TYPE_FOR_TREE } from "@/constants/traces";
import useTreeDetailsStore, {
  SpanWithMetadata,
  TreeNode,
} from "@/v2/pages-shared/traces/TraceDetailsPanel/TreeDetailsStore";
import { SELECTED_TREE_DATABLOCKS_DEFAULT_VALUE } from "@/v2/pages-shared/traces/TraceDetailsPanel/treeConfig";
import VirtualizedTreeViewer from "./VirtualizedTreeViewer";

const ENTER = { key: "Enter", code: "Enter" };

const buildNode = (
  id: string,
  type: string,
  children?: TreeNode[],
): TreeNode => ({
  id,
  name: id,
  data: { id, name: id, type } as unknown as SpanWithMetadata,
  children,
});

const TreeInPanel = () => {
  const scrollRef = useRef<HTMLDivElement>(null);
  return (
    <div ref={scrollRef}>
      <button>Panel body</button>
      <VirtualizedTreeViewer
        scrollRef={scrollRef}
        config={SELECTED_TREE_DATABLOCKS_DEFAULT_VALUE}
        rowId="trace"
        onRowIdChange={vi.fn()}
      />
    </div>
  );
};

const renderTree = (extra?: ReactNode) =>
  render(
    <TooltipProvider>
      <TreeInPanel />
      {extra}
    </TooltipProvider>,
  );

const isTraceExpanded = () =>
  useTreeDetailsStore.getState().expandedTreeRows.has("trace");

describe("VirtualizedTreeViewer Enter hotkey", () => {
  beforeEach(() => {
    useTreeDetailsStore
      .getState()
      .setTree([
        buildNode("trace", TRACE_TYPE_FOR_TREE, [buildNode("span", "llm")]),
      ]);
  });

  it("collapses the selected row on Enter pressed in the panel", () => {
    renderTree();

    fireEvent.keyDown(
      screen.getByRole("button", { name: "Panel body" }),
      ENTER,
    );

    expect(isTraceExpanded()).toBe(false);
  });

  it("leaves Enter in a confirm dialog to the dialog", () => {
    renderTree(
      <Dialog open>
        <DialogContent aria-describedby={undefined}>
          <DialogTitle>Delete trace</DialogTitle>
          <button>Cancel</button>
        </DialogContent>
      </Dialog>,
    );

    const event = fireEvent.keyDown(
      screen.getByRole("button", { name: "Cancel" }),
      ENTER,
    );

    expect(event).toBe(true);
    expect(isTraceExpanded()).toBe(true);
  });

  it("ignores Enter in the panel while a modal it opened left focus there", () => {
    renderTree(
      <Dialog open>
        <DialogContent
          aria-describedby={undefined}
          onOpenAutoFocus={(event) => event.preventDefault()}
        >
          <DialogTitle>Image preview</DialogTitle>
        </DialogContent>
      </Dialog>,
    );

    fireEvent.keyDown(
      screen.getByRole("button", { name: "Panel body", hidden: true }),
      ENTER,
    );

    expect(isTraceExpanded()).toBe(true);
  });
});

import { beforeEach, describe, expect, it } from "vitest";
import useTreeDetailsStore, { TreeNode } from "./TreeDetailsStore";

const node = (id: string, children: TreeNode[] = []): TreeNode =>
  ({ id, name: id, data: {}, children }) as unknown as TreeNode;

const TREE = [
  node("trace", [
    node("summarizer", [node("wrapper", [node("sub_prepare")])]),
    node("worker"),
  ]),
];

const visibleIds = () =>
  useTreeDetailsStore.getState().flattenedTree.map((row) => row.id);

describe("TreeDetailsStore expandToRow", () => {
  beforeEach(() => {
    useTreeDetailsStore.getState().setTree(TREE);
  });

  it("should expand every collapsed ancestor of the row", () => {
    const { setExpandedTreeRows, expandToRow } = useTreeDetailsStore.getState();
    setExpandedTreeRows(new Set(["trace"]));
    expect(visibleIds()).not.toContain("sub_prepare");

    expandToRow("sub_prepare");

    expect(visibleIds()).toContain("sub_prepare");
    expect(useTreeDetailsStore.getState().expandedTreeRows).toEqual(
      new Set(["trace", "summarizer", "wrapper"]),
    );
  });

  it("should keep the expanded rows untouched when the row is already visible or unknown", () => {
    const { setExpandedTreeRows, expandToRow } = useTreeDetailsStore.getState();
    const expanded = new Set(["trace", "summarizer", "wrapper"]);
    setExpandedTreeRows(expanded);

    expandToRow("sub_prepare");
    expandToRow("missing");

    expect(useTreeDetailsStore.getState().expandedTreeRows).toBe(expanded);
  });
});

import { describe, it, expect } from "vitest";
import {
  parseJsonPointer,
  applyPatch,
  applyPatches,
  createPropPatch,
  createAddNodePatch,
  createRemoveNodePatch,
  createAddChildPatch,
  mergeViewTrees,
  createEmptyTree,
} from "./patches";
import type { ViewTree, ViewPatch, ViewNode } from "./types";

describe("patches", () => {
  describe("parseJsonPointer", () => {
    it("parses simple path", () => {
      const segments = parseJsonPointer("/nodes");
      expect(segments).toEqual(["nodes"]);
    });

    it("parses nested path", () => {
      const segments = parseJsonPointer("/nodes/section-1/props/title");
      expect(segments).toEqual(["nodes", "section-1", "props", "title"]);
    });

    it("handles escaped characters", () => {
      const segments = parseJsonPointer("/nodes/path~1with~0tilde");
      expect(segments).toEqual(["nodes", "path/with~tilde"]);
    });

    it("returns empty array for root pointer", () => {
      const segments = parseJsonPointer("/");
      expect(segments).toEqual([]);
    });

    it("throws for invalid pointer", () => {
      expect(() => parseJsonPointer("nodes")).toThrow("Invalid JSON Pointer");
    });
  });

  describe("applyPatch", () => {
    const baseTree: ViewTree = {
      version: 1,
      root: "root",
      nodes: {
        root: {
          id: "root",
          type: "Section",
          props: { title: "Root Section" },
          children: ["child1"],
        },
        child1: {
          id: "child1",
          type: "KeyValue",
          props: { label: "Label", value: { $path: "model" } },
        },
      },
    };

    it("applies add operation", () => {
      const patch: ViewPatch = {
        op: "add",
        path: "/nodes/newNode",
        value: {
          id: "newNode",
          type: "KeyValue",
          props: { label: "New", value: "test" },
        },
      };

      const result = applyPatch(baseTree, patch);

      expect(result.nodes.newNode).toBeDefined();
      expect(result.nodes.newNode.type).toBe("KeyValue");
    });

    it("applies replace operation", () => {
      const patch: ViewPatch = {
        op: "replace",
        path: "/nodes/root/props/title",
        value: "Updated Title",
      };

      const result = applyPatch(baseTree, patch);

      expect(result.nodes.root.props.title).toBe("Updated Title");
    });

    it("applies remove operation", () => {
      const patch: ViewPatch = {
        op: "remove",
        path: "/nodes/child1",
      };

      const result = applyPatch(baseTree, patch);

      expect(result.nodes.child1).toBeUndefined();
    });

    it("applies move operation", () => {
      const treeWithTwoChildren: ViewTree = {
        ...baseTree,
        nodes: {
          ...baseTree.nodes,
          child2: {
            id: "child2",
            type: "KeyValue",
            props: { label: "Child 2", value: "v2" },
          },
        },
      };

      const patch: ViewPatch = {
        op: "move",
        from: "/nodes/child2",
        path: "/nodes/movedChild",
      };

      const result = applyPatch(treeWithTwoChildren, patch);

      expect(result.nodes.child2).toBeUndefined();
      expect(result.nodes.movedChild).toBeDefined();
      expect(result.nodes.movedChild.props.label).toBe("Child 2");
    });

    it("updates meta.updatedAt on patch", () => {
      const patch: ViewPatch = {
        op: "replace",
        path: "/nodes/root/props/title",
        value: "Updated",
      };

      const result = applyPatch(baseTree, patch);

      expect(result.meta?.updatedAt).toBeDefined();
    });

    it("does not mutate original tree", () => {
      const patch: ViewPatch = {
        op: "replace",
        path: "/nodes/root/props/title",
        value: "Changed",
      };

      const result = applyPatch(baseTree, patch);

      expect(baseTree.nodes.root.props.title).toBe("Root Section");
      expect(result.nodes.root.props.title).toBe("Changed");
    });

    it("applies add operation with array append syntax (-)", () => {
      const tree: ViewTree = {
        version: 1,
        root: "root",
        nodes: {
          root: {
            id: "root",
            type: "Section",
            props: {},
            children: ["existing-child"],
          },
        },
      };

      const patch: ViewPatch = {
        op: "add",
        path: "/nodes/root/children/-",
        value: "new-child",
      };

      const result = applyPatch(tree, patch);

      expect(result.nodes.root.children).toEqual([
        "existing-child",
        "new-child",
      ]);
    });
  });

  describe("applyPatches", () => {
    it("applies multiple patches in sequence", () => {
      const tree = createEmptyTree();
      const patches: ViewPatch[] = [
        { op: "add", path: "/root", value: "root" },
        {
          op: "add",
          path: "/nodes/root",
          value: {
            id: "root",
            type: "Section",
            props: { title: "Root" },
          },
        },
      ];

      const result = applyPatches(tree, patches);

      expect(result.root).toBe("root");
      expect(result.nodes.root).toBeDefined();
    });
  });

  describe("patch creation helpers", () => {
    describe("createPropPatch", () => {
      it("creates a replace patch for a prop", () => {
        const patch = createPropPatch("node1", "title", "New Title");

        expect(patch).toEqual({
          op: "replace",
          path: "/nodes/node1/props/title",
          value: "New Title",
        });
      });
    });

    describe("createAddNodePatch", () => {
      it("creates an add patch for a node with parentKey", () => {
        const node: ViewNode = {
          id: "newNode",
          type: "KeyValue",
          props: { label: "Test", value: "val" },
        };

        const patch = createAddNodePatch(node);

        expect(patch).toEqual({
          op: "add",
          path: "/nodes/newNode",
          value: {
            ...node,
            parentKey: null,
          },
        });
      });

      it("sets parentKey when parentId is provided", () => {
        const node: ViewNode = {
          id: "childNode",
          type: "KeyValue",
          props: { label: "Child", value: "v" },
        };

        const patch = createAddNodePatch(node, "parentNode");

        expect(patch.value).toEqual({
          ...node,
          parentKey: "parentNode",
        });
      });
    });

    describe("createRemoveNodePatch", () => {
      it("creates a remove patch for a node", () => {
        const patch = createRemoveNodePatch("oldNode");

        expect(patch).toEqual({
          op: "remove",
          path: "/nodes/oldNode",
        });
      });
    });

    describe("createAddChildPatch", () => {
      it("creates an add patch for a child with index", () => {
        const patch = createAddChildPatch("parent", "child", 0);

        expect(patch).toEqual({
          op: "add",
          path: "/nodes/parent/children/0",
          value: "child",
        });
      });

      it("creates an append patch for a child without index", () => {
        const patch = createAddChildPatch("parent", "child");

        expect(patch).toEqual({
          op: "add",
          path: "/nodes/parent/children/-",
          value: "child",
        });
      });
    });
  });

  describe("mergeViewTrees", () => {
    const existingTree: ViewTree = {
      version: 1,
      root: "root",
      nodes: {
        root: {
          id: "root",
          type: "Section",
          props: { title: "User Edited Title" },
          children: [],
        },
      },
    };

    it("applies patches to existing tree", () => {
      const patches: ViewPatch[] = [
        {
          op: "add",
          path: "/nodes/newNode",
          value: {
            id: "newNode",
            type: "KeyValue",
            props: { label: "New", value: "v" },
          },
        },
      ];

      const result = mergeViewTrees(existingTree, patches);

      expect(result.nodes.newNode).toBeDefined();
    });

    it("preserves specified props from replacement", () => {
      const patches: ViewPatch[] = [
        {
          op: "replace",
          path: "/nodes/root/props/title",
          value: "AI Generated Title",
        },
      ];

      const result = mergeViewTrees(existingTree, patches, {
        preserveProps: ["title"],
      });

      expect(result.nodes.root.props.title).toBe("User Edited Title");
    });

    it("allows non-preserved props to be replaced", () => {
      const treeWithDescription: ViewTree = {
        ...existingTree,
        nodes: {
          ...existingTree.nodes,
          root: {
            ...existingTree.nodes.root,
            props: {
              ...existingTree.nodes.root.props,
              description: "Old description",
            },
          },
        },
      };

      const patches: ViewPatch[] = [
        {
          op: "replace",
          path: "/nodes/root/props/description",
          value: "New description",
        },
      ];

      const result = mergeViewTrees(treeWithDescription, patches, {
        preserveProps: ["title"],
      });

      expect(result.nodes.root.props.description).toBe("New description");
    });
  });

  describe("createEmptyTree", () => {
    it("creates a valid empty tree", () => {
      const tree = createEmptyTree();

      expect(tree).toEqual({
        version: 1,
        root: "",
        nodes: {},
      });
    });
  });
});

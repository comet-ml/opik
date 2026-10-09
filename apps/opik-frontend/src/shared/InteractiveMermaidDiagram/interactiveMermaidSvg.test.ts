import { describe, expect, it } from "vitest";
import langGraphSvg from "./__fixtures__/langgraph-flowchart.mermaid-11.4.1.svg?raw";
import adkSvg from "./__fixtures__/adk-flowchart.mermaid-11.4.1.svg?raw";
import {
  getMermaidNodeIndex,
  getMermaidNodeStyles,
  indexMermaidNodes,
  MermaidNode,
  NODE_INDEX_ATTRIBUTE,
} from "./interactiveMermaidSvg";

const DIAGRAM_ID = "mermaid-diagram-fixture";

const describeNodes = (nodes: MermaidNode[]) =>
  nodes.map((node) => `${node.isSubgraph ? "subgraph" : "node"}:${node.id}`);

const parseSvg = (svg: string) => {
  const container = document.createElement("div");
  container.append(
    new DOMParser().parseFromString(svg, "image/svg+xml").documentElement,
  );
  return container;
};

const indexFixture = (svg: string) => {
  const container = parseSvg(svg);
  const nodes = indexMermaidNodes(container.querySelector("svg")!, DIAGRAM_ID);
  return { container, nodes };
};

describe("indexMermaidNodes", () => {
  it("should index LangGraph nodes and subgraphs of Mermaid's flowchart SVG", () => {
    const { nodes } = indexFixture(langGraphSvg);

    expect(describeNodes(nodes).sort()).toEqual([
      "node:__end__",
      "node:__start__",
      "node:router",
      "node:summarizer\\3asub_prepare",
      "node:summarizer\\3asub_summarize",
      "node:worker",
      "subgraph:summarizer",
    ]);
  });

  it("should index ADK node ids with colons", () => {
    const { nodes } = indexFixture(adkSvg);

    expect(describeNodes(nodes).sort()).toEqual([
      "node:Tools:get_weather",
      "node:reviewer",
      "node:root_agent",
      "node:writer",
      "subgraph:pipeline",
    ]);
  });

  it("should tag every node with its index and add one scoped rule set per node", () => {
    const { container, nodes } = indexFixture(adkSvg);

    nodes.forEach((node, index) => {
      expect(
        container.querySelector(`[${NODE_INDEX_ATTRIBUTE}="${index}"]`),
      ).not.toBeNull();
    });
    const style = container.querySelector("svg > style")?.textContent ?? "";
    expect(style).toContain("/* mermaid styles */");
    expect(style).toContain(
      `#${DIAGRAM_ID} [${NODE_INDEX_ATTRIBUTE}="0"]{cursor:var(--mermaid-node-0-cursor)`,
    );
    expect(style).not.toContain(`[${NODE_INDEX_ATTRIBUTE}="${nodes.length}"]`);
  });

  it("should prefer data-id over the generated element id", () => {
    const { nodes } = indexFixture(
      '<svg xmlns="http://www.w3.org/2000/svg"><style></style><g class="node" id="flowchart-old-1" data-id="new"></g></svg>',
    );

    expect(nodes).toEqual([{ id: "new", isSubgraph: false }]);
  });
});

describe("getMermaidNodeStyles", () => {
  const nodes: MermaidNode[] = [
    { id: "team", isSubgraph: true },
    { id: "planner", isSubgraph: false },
    { id: "worker", isSubgraph: false },
    { id: "__end__", isSubgraph: false },
  ];

  it("should set cursor and selection variables per node", () => {
    const styles = getMermaidNodeStyles(nodes, {
      isNodeClickable: (id) => ["team", "planner", "worker"].includes(id),
      selectedNodeIds: ["worker", "planner", "team"],
    }) as Record<string, string>;

    expect(styles["--mermaid-node-1-cursor"]).toBe("pointer");
    expect(styles["--mermaid-node-2-cursor"]).toBe("pointer");
    expect(styles["--mermaid-node-3-cursor"]).toBeUndefined();
    expect(Object.keys(styles).some((key) => key.endsWith("-opacity"))).toBe(
      false,
    );
    expect(styles["--mermaid-node-2-extra-stroke-width"]).toBe("2px");
    expect(styles["--mermaid-node-1-extra-stroke-width"]).toBeUndefined();
    expect(styles["--mermaid-node-0-extra-stroke-width"]).toBe("2px");
  });

  it("should leave the graph as is when no node is clickable", () => {
    expect(
      getMermaidNodeStyles(nodes, {
        isNodeClickable: () => false,
        selectedNodeIds: ["worker"],
      }),
    ).toEqual({});
  });
});

describe("getMermaidNodeIndex", () => {
  it("should find the node a pointer event landed in", () => {
    const { container } = indexFixture(langGraphSvg);
    const label = container.querySelector(`[${NODE_INDEX_ATTRIBUTE}="3"] *`)!;

    expect(getMermaidNodeIndex(label, container)).toBe(3);
    expect(getMermaidNodeIndex(container, container)).toBeNull();
  });
});

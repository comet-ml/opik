import { layoutLoop } from "./loopLayout";

const node = (id: string, kind: string, name = id) => ({ id, kind, name });

describe("layoutLoop", () => {
  it("orders columns by where the kind sits in the loop, unknown kinds last", () => {
    const { nodes } = layoutLoop({
      nodes: [
        node("prompt:1", "prompt"),
        node("x:1", "mystery"),
        node("suite:1", "test_suite"),
        node("production", "project"),
      ],
      edges: [],
    });
    const columns = Object.fromEntries(nodes.map((n) => [n.id, n.column]));
    expect(columns).toEqual({
      production: 0,
      "suite:1": 1,
      "prompt:1": 2,
      "x:1": 3,
    });
  });

  it("stacks a column's nodes by name so the picture is stable between reads", () => {
    const { nodes } = layoutLoop({
      nodes: [
        node("suite:b", "test_suite", "Smoke"),
        node("suite:a", "test_suite", "Regression"),
      ],
      edges: [],
    });
    expect(nodes.map((n) => n.name)).toEqual(["Regression", "Smoke"]);
    expect(nodes[0].y).toBeLessThan(nodes[1].y);
  });

  it("draws forward edges, routes back-edges under the nodes and skips unknown ends", () => {
    const { edges, skipped, height } = layoutLoop({
      nodes: [
        node("production", "project"),
        node("suite:1", "test_suite"),
        node("prompt:1", "prompt"),
      ],
      edges: [
        {
          automation_id: "a",
          name: "collect",
          recipe: "collect",
          from: "production",
          to: ["suite:1", "queue:missing"],
          produced: {},
        },
        {
          automation_id: "b",
          name: "improve",
          recipe: "improve",
          from: "prompt:1",
          to: ["production"],
          produced: {},
        },
      ],
    });
    expect(skipped).toBe(1);
    expect(edges.map((e) => [e.key, e.backward])).toEqual([
      ["a:suite:1", false],
      ["b:production", true],
    ]);
    const plain = layoutLoop({
      nodes: [node("production", "project")],
      edges: [],
    });
    expect(height).toBeGreaterThan(plain.height);
  });
});

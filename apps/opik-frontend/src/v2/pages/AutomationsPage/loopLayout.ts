import { Loop, LoopNode } from "@/types/event-automations";

// Columns follow the loop: production is scored by judges, sampled into queues, kept in
// suites, which gate and improve prompts; CI and datasets hang off the end. Unknown kinds
// go after everything.
const KIND_ORDER = [
  "project",
  "judge",
  "annotation_queue",
  "test_suite",
  "prompt",
  "ci",
  "dataset",
];

export const NODE_WIDTH = 168;
export const NODE_HEIGHT = 44;
const COLUMN_GAP = 96;
const ROW_GAP = 20;
const PADDING = 12;
// Back-edges (a suite improving a prompt, a prompt back to production) travel this far
// below the nodes so they never cross the forward ones.
const BACK_LANE = 40;
const PARALLEL_OFFSET = 12;

export type LaidOutNode = LoopNode & { column: number; x: number; y: number };

export type LaidOutEdge = {
  key: string;
  automation_id: string;
  name: string;
  recipe: string | null;
  from: string;
  to: string;
  backward: boolean;
  path: string;
  labelX: number;
  labelY: number;
};

export type LoopLayout = {
  nodes: LaidOutNode[];
  edges: LaidOutEdge[];
  width: number;
  height: number;
  // Edge ends the backend named but did not list as nodes; drawn nowhere, listed in the table.
  skipped: number;
};

export const columnOf = (kind: string) => {
  const index = KIND_ORDER.indexOf(kind);
  return index === -1 ? KIND_ORDER.length : index;
};

type Point = [number, number];

const cubic = (p0: Point, p1: Point, p2: Point, p3: Point) => ({
  path: `M ${p0[0]} ${p0[1]} C ${p1[0]} ${p1[1]}, ${p2[0]} ${p2[1]}, ${p3[0]} ${p3[1]}`,
  // The curve's point at t = 0.5, where the label sits.
  labelX: (p0[0] + 3 * p1[0] + 3 * p2[0] + p3[0]) / 8,
  labelY: (p0[1] + 3 * p1[1] + 3 * p2[1] + p3[1]) / 8,
});

const edgeGeometry = (
  a: LaidOutNode,
  b: LaidOutNode,
  laneY: number,
  offset: number,
) => {
  if (b.column > a.column) {
    const start: Point = [a.x + NODE_WIDTH, a.y + NODE_HEIGHT / 2];
    const end: Point = [b.x, b.y + NODE_HEIGHT / 2];
    const bend = (end[0] - start[0]) / 2;
    return {
      backward: false,
      ...cubic(
        start,
        [start[0] + bend, start[1] + offset],
        [end[0] - bend, end[1] + offset],
        end,
      ),
    };
  }
  if (b.column === a.column) {
    const start: Point = [a.x + NODE_WIDTH, a.y + NODE_HEIGHT / 2];
    const end: Point = [b.x + NODE_WIDTH, b.y + NODE_HEIGHT / 2];
    const bulge = 48 + offset;
    return {
      backward: false,
      ...cubic(
        start,
        [start[0] + bulge, start[1] - 20],
        [end[0] + bulge, end[1] + 20],
        end,
      ),
    };
  }
  const start: Point = [a.x + NODE_WIDTH / 2, a.y + NODE_HEIGHT];
  const end: Point = [b.x + NODE_WIDTH / 2, b.y + NODE_HEIGHT];
  return {
    backward: true,
    ...cubic(start, [start[0], laneY + offset], [end[0], laneY + offset], end),
  };
};

export const layoutLoop = (loop: Pick<Loop, "nodes" | "edges">): LoopLayout => {
  const columns = [
    ...new Set(loop.nodes.map((node) => columnOf(node.kind))),
  ].sort((a, b) => a - b);
  const nodes: LaidOutNode[] = [];
  let rows = 0;
  columns.forEach((kindColumn, column) => {
    const members = loop.nodes
      .filter((node) => columnOf(node.kind) === kindColumn)
      .sort((a, b) => a.name.localeCompare(b.name) || a.id.localeCompare(b.id));
    rows = Math.max(rows, members.length);
    members.forEach((node, row) =>
      nodes.push({
        ...node,
        column,
        x: PADDING + column * (NODE_WIDTH + COLUMN_GAP),
        y: PADDING + row * (NODE_HEIGHT + ROW_GAP),
      }),
    );
  });

  const byId = new Map(nodes.map((node) => [node.id, node]));
  const bottom = PADDING + rows * (NODE_HEIGHT + ROW_GAP) - ROW_GAP;
  const laneY = bottom + BACK_LANE;
  const seenPairs = new Map<string, number>();
  const edges: LaidOutEdge[] = [];
  let skipped = 0;

  loop.edges.forEach((edge) =>
    edge.to.forEach((to) => {
      const a = byId.get(edge.from);
      const b = byId.get(to);
      if (!a || !b) {
        skipped += 1;
        return;
      }
      const pair = `${a.id}>${b.id}`;
      const parallel = seenPairs.get(pair) ?? 0;
      seenPairs.set(pair, parallel + 1);
      edges.push({
        key: `${edge.automation_id}:${to}`,
        automation_id: edge.automation_id,
        name: edge.name,
        recipe: edge.recipe ?? null,
        from: a.id,
        to: b.id,
        ...edgeGeometry(a, b, laneY, parallel * PARALLEL_OFFSET),
      });
    }),
  );

  const hasBackEdge = edges.some((edge) => edge.backward);
  return {
    nodes,
    edges,
    width:
      PADDING * 2 +
      columns.length * NODE_WIDTH +
      Math.max(0, columns.length - 1) * COLUMN_GAP,
    height: Math.max(bottom, 0) + PADDING + (hasBackEdge ? BACK_LANE + 8 : 0),
    skipped,
  };
};

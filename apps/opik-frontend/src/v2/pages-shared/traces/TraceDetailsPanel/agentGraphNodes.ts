import get from "lodash/get";
import uniq from "lodash/uniq";

import { Span, Trace } from "@/types/traces";
import { isSpanHiddenByDefault } from "@/v2/pages-shared/traces/spanVisibility";

const GRAPH_NODE_ID_PATH = ["_opik", "graph_node_id"];
const LANGGRAPH_NODE_KEY = "langgraph_node";
const LANGGRAPH_NAMESPACE_KEY = "langgraph_checkpoint_ns";

export const toMermaidSafeId = (value: string) =>
  value.replace(
    /[^a-zA-Z0-9_-]/gu,
    (char) => `\\${char.codePointAt(0)!.toString(16)}`,
  );

type TreeRow = Span | Trace;

const getParentId = (row: TreeRow) =>
  "trace_id" in row ? row.parent_span_id || row.trace_id : "";

const getLangGraphNodePath = (row: TreeRow, node: string) => {
  const namespace: unknown = get(row.metadata, LANGGRAPH_NAMESPACE_KEY);
  if (typeof namespace !== "string" || !namespace) return node;

  const names = namespace.split("|").map((part) => part.split(":")[0]);
  return names[names.length - 1] === node ? names.join(":") : node;
};

const getRowGraphNodeIds = (row: TreeRow): string[] => {
  if (!row.name || isSpanHiddenByDefault(row)) return [];

  const graphNodeId: unknown = get(row.metadata, GRAPH_NODE_ID_PATH);
  if (typeof graphNodeId === "string") return [graphNodeId];

  const langGraphNode: unknown = get(row.metadata, LANGGRAPH_NODE_KEY);
  if (typeof langGraphNode === "string") {
    if (row.name !== langGraphNode || langGraphNode.startsWith("__")) {
      return [];
    }
    const path = getLangGraphNodePath(row, langGraphNode);
    return uniq([path, toMermaidSafeId(path)]);
  }

  return uniq([row.name, row.name.replace(/ /g, "_")]);
};

export type AgentGraphNodeIndex = {
  rowsByNodeId: Map<string, TreeRow[]>;
  nodeIdsByRowId: Map<string, string[]>;
};

export const buildAgentGraphNodeIndex = (
  rows: TreeRow[],
): AgentGraphNodeIndex => {
  const rowsByNodeId = new Map<string, TreeRow[]>();
  const nodeIdsByRowId = new Map<string, string[]>();

  const sortedRows = [...rows].sort((r1, r2) =>
    (r1.start_time ?? "").localeCompare(r2.start_time ?? ""),
  );

  sortedRows.forEach((row) => {
    const nodeIds = getRowGraphNodeIds(row);
    if (!nodeIds.length) return;

    nodeIdsByRowId.set(row.id, nodeIds);
    nodeIds.forEach((nodeId) => {
      const nodeRows = rowsByNodeId.get(nodeId) ?? [];
      nodeRows.push(row);
      rowsByNodeId.set(nodeId, nodeRows);
    });
  });

  return { rowsByNodeId, nodeIdsByRowId };
};

export const getRowAncestorNodeIds = (
  rowId: string,
  rowsById: Map<string, TreeRow>,
  { nodeIdsByRowId }: AgentGraphNodeIndex,
): string[] => {
  const nodeIds: string[] = [];
  const visited = new Set<string>();
  let current = rowsById.get(rowId);

  while (current && !visited.has(current.id)) {
    visited.add(current.id);
    nodeIds.push(...(nodeIdsByRowId.get(current.id) ?? []));
    current = rowsById.get(getParentId(current));
  }

  return nodeIds;
};

export const isTraceRow = (row: TreeRow) => !("trace_id" in row);

export const getCurrentRunIndex = (
  nodeRows: TreeRow[],
  rowId: string,
  rowsById: Map<string, TreeRow>,
): number => {
  const runIndexById = new Map(nodeRows.map((row, index) => [row.id, index]));
  const visited = new Set<string>();
  let current = rowsById.get(rowId);

  while (current && !visited.has(current.id)) {
    const index = runIndexById.get(current.id);
    if (index !== undefined) return index;
    visited.add(current.id);
    current = rowsById.get(getParentId(current));
  }

  return -1;
};

export const getNextNodeRow = (
  nodeRows: TreeRow[],
  rowId: string,
  rowsById: Map<string, TreeRow>,
): TreeRow => {
  const index = getCurrentRunIndex(nodeRows, rowId, rowsById);
  return nodeRows[(index + 1) % nodeRows.length];
};

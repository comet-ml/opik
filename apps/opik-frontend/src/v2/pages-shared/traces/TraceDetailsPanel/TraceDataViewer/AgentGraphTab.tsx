import React, { useCallback, useMemo } from "react";
import { AgentGraphData, Span, Trace } from "@/types/traces";
import MermaidDiagram from "@/shared/MermaidDiagram/MermaidDiagram";
import InteractiveMermaidDiagram from "@/shared/InteractiveMermaidDiagram/InteractiveMermaidDiagram";
import ZoomPanContainer from "@/shared/ZoomPanContainer/ZoomPanContainer";
import {
  buildAgentGraphNodeIndex,
  getNextNodeRow,
  getRowAncestorNodeIds,
} from "@/v2/pages-shared/traces/TraceDetailsPanel/agentGraphNodes";

type AgentGraphTabProps = {
  data: AgentGraphData;
  treeData?: Array<Trace | Span>;
  rowId?: string;
  onSelectRow?: (id: string) => void;
};

const AgentGraphTab: React.FC<AgentGraphTabProps> = ({
  data,
  treeData,
  rowId = "",
  onSelectRow,
}) => {
  const nodeIndex = useMemo(
    () => buildAgentGraphNodeIndex(treeData ?? []),
    [treeData],
  );

  const rowsById = useMemo(
    () => new Map((treeData ?? []).map((row) => [row.id, row])),
    [treeData],
  );

  const selectedNodeIds = useMemo(
    () => getRowAncestorNodeIds(rowId, rowsById, nodeIndex),
    [rowId, rowsById, nodeIndex],
  );

  const isNodeClickable = useCallback(
    (nodeId: string) => nodeIndex.rowsByNodeId.has(nodeId),
    [nodeIndex],
  );

  const getNodeTooltip = useCallback(
    (nodeId: string) => {
      const nodeRows = nodeIndex.rowsByNodeId.get(nodeId) ?? [];
      if (nodeRows.length < 2) return "Open span";

      const runIndex = nodeRows.findIndex((row) => row.id === rowId);
      return runIndex === -1
        ? `Ran ${nodeRows.length} times. Click to open the first run`
        : `Run ${runIndex + 1} of ${
            nodeRows.length
          }. Click to open the next run`;
    },
    [nodeIndex, rowId],
  );

  const handleNodeClick = useCallback(
    (nodeId: string) => {
      const nodeRows = nodeIndex.rowsByNodeId.get(nodeId);
      if (!nodeRows?.length) return;
      onSelectRow?.(getNextNodeRow(nodeRows, rowId).id);
    },
    [nodeIndex, onSelectRow, rowId],
  );

  return (
    <ZoomPanContainer dialogTitle="Agent graph" expandButton={false}>
      {onSelectRow ? (
        <InteractiveMermaidDiagram
          chart={data.data}
          isNodeClickable={isNodeClickable}
          onNodeClick={handleNodeClick}
          getNodeTooltip={getNodeTooltip}
          selectedNodeIds={selectedNodeIds}
        />
      ) : (
        <MermaidDiagram chart={data.data} />
      )}
    </ZoomPanContainer>
  );
};

export default AgentGraphTab;

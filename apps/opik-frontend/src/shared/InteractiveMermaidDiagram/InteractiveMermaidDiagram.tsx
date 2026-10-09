import React, { useCallback, useMemo, useRef, useState } from "react";

import MermaidDiagramError from "@/shared/MermaidDiagram/MermaidDiagramError";
import useMermaidSvg, {
  MERMAID_CONTAINER_CLASSNAME,
} from "@/shared/MermaidDiagram/useMermaidSvg";
import {
  getMermaidNodeIndex,
  getMermaidNodeStyles,
  makeMermaidSvgInteractive,
} from "@/shared/InteractiveMermaidDiagram/interactiveMermaidSvg";

const CLICK_MOVE_TOLERANCE = 4;

type InteractiveMermaidDiagramProps = {
  chart: string;
  isNodeClickable: (nodeId: string) => boolean;
  onNodeClick: (nodeId: string) => void;
  getNodeTooltip?: (nodeId: string) => string | undefined;
  selectedNodeIds?: string[];
};

const InteractiveMermaidDiagram: React.FC<InteractiveMermaidDiagramProps> = ({
  chart,
  isNodeClickable,
  onNodeClick,
  getNodeTooltip,
  selectedNodeIds,
}) => {
  const { svg, hasError, diagramId } = useMermaidSvg(chart);
  const [hoveredIndex, setHoveredIndex] = useState<number | null>(null);
  const pointerDownRef = useRef<{ x: number; y: number } | null>(null);

  const interactiveSvg = useMemo(
    () =>
      svg ? makeMermaidSvgInteractive(svg, diagramId) : { svg, nodes: [] },
    [svg, diagramId],
  );

  const nodeStyles = useMemo(
    () =>
      getMermaidNodeStyles(interactiveSvg.nodes, {
        isNodeClickable,
        selectedNodeIds,
      }),
    [interactiveSvg.nodes, isNodeClickable, selectedNodeIds],
  );

  const getClickableNodeId = useCallback(
    (index: number | null) => {
      const node = index === null ? undefined : interactiveSvg.nodes[index];
      return node && isNodeClickable(node.id) ? node.id : undefined;
    },
    [interactiveSvg.nodes, isNodeClickable],
  );

  const handleMouseMove = useCallback((event: React.MouseEvent) => {
    setHoveredIndex(getMermaidNodeIndex(event.target, event.currentTarget));
  }, []);

  const handleMouseDown = useCallback((event: React.MouseEvent) => {
    pointerDownRef.current = { x: event.clientX, y: event.clientY };
  }, []);

  const handleClick = useCallback(
    (event: React.MouseEvent) => {
      const start = pointerDownRef.current;
      pointerDownRef.current = null;
      if (
        start &&
        (Math.abs(event.clientX - start.x) > CLICK_MOVE_TOLERANCE ||
          Math.abs(event.clientY - start.y) > CLICK_MOVE_TOLERANCE)
      ) {
        return;
      }

      const nodeId = getClickableNodeId(
        getMermaidNodeIndex(event.target, event.currentTarget),
      );
      if (nodeId) onNodeClick(nodeId);
    },
    [getClickableNodeId, onNodeClick],
  );

  if (hasError) return <MermaidDiagramError />;

  const hoveredNodeId = getClickableNodeId(hoveredIndex);

  return (
    <div
      dangerouslySetInnerHTML={{
        __html: interactiveSvg.svg,
      }}
      style={nodeStyles}
      title={hoveredNodeId ? getNodeTooltip?.(hoveredNodeId) : undefined}
      onMouseMove={handleMouseMove}
      onMouseLeave={() => setHoveredIndex(null)}
      onMouseDown={handleMouseDown}
      onClick={handleClick}
      className={MERMAID_CONTAINER_CLASSNAME}
    />
  );
};

export default InteractiveMermaidDiagram;

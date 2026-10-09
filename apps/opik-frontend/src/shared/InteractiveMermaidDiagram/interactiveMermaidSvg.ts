import { CSSProperties } from "react";

export const NODE_INDEX_ATTRIBUTE = "data-node-index";

const NODE_SELECTOR = "g.node, g.cluster";
const FLOWCHART_NODE_ID_REGEX = /^flowchart-(.+)-\d+$/;
const NODE_SHAPE_SELECTOR = ":is(rect,polygon,path,circle,ellipse)";
const SELECTED_EXTRA_STROKE_WIDTH = "2px";

export type MermaidNode = {
  id: string;
  isSubgraph: boolean;
};

export type InteractiveMermaidSvg = {
  svg: string;
  nodes: MermaidNode[];
};

export type MermaidNodeStateOptions = {
  isNodeClickable: (nodeId: string) => boolean;
  selectedNodeIds?: string[];
};

const nodeVariable = (index: number, property: string) =>
  `--mermaid-node-${index}-${property}`;

const toMermaidNode = (element: Element): MermaidNode | null => {
  const isSubgraph = element.classList.contains("cluster");
  const rawId = isSubgraph
    ? element.id
    : element.getAttribute("data-id") ?? element.id;
  const id = rawId.match(FLOWCHART_NODE_ID_REGEX)?.[1] ?? rawId;

  return id ? { id, isSubgraph } : null;
};

const buildNodeRules = (diagramId: string, index: number) => {
  const node = `#${diagramId} [${NODE_INDEX_ATTRIBUTE}="${index}"]`;
  return [
    `${node}{cursor:var(${nodeVariable(index, "cursor")},auto)}`,
    `${node}:hover>*{filter:var(${nodeVariable(index, "hover-filter")},none)}`,
    `${node}>${NODE_SHAPE_SELECTOR}{stroke-width:calc(1px + var(${nodeVariable(
      index,
      "extra-stroke-width",
    )},0px))}`,
  ].join("");
};

export const indexMermaidNodes = (
  svgElement: Element,
  diagramId: string,
): MermaidNode[] => {
  const nodes: MermaidNode[] = [];
  svgElement.querySelectorAll(NODE_SELECTOR).forEach((element) => {
    const node = toMermaidNode(element);
    if (!node) return;
    element.setAttribute(NODE_INDEX_ATTRIBUTE, String(nodes.length));
    nodes.push(node);
  });

  const style =
    svgElement.querySelector(":scope > style") ??
    svgElement.insertBefore(
      svgElement.ownerDocument.createElementNS(
        svgElement.namespaceURI,
        "style",
      ),
      svgElement.firstChild,
    );
  style.textContent += nodes
    .map((_, index) => buildNodeRules(diagramId, index))
    .join("");

  return nodes;
};

export const makeMermaidSvgInteractive = (
  svg: string,
  diagramId: string,
): InteractiveMermaidSvg => {
  const svgElement = new DOMParser()
    .parseFromString(svg, "text/html")
    .querySelector("svg");
  if (!svgElement) return { svg, nodes: [] };

  const nodes = indexMermaidNodes(svgElement, diagramId);
  return { svg: svgElement.outerHTML, nodes };
};

export const getMermaidNodeStyles = (
  nodes: MermaidNode[],
  { isNodeClickable, selectedNodeIds = [] }: MermaidNodeStateOptions,
): CSSProperties => {
  if (!nodes.some((node) => isNodeClickable(node.id))) return {};

  const selectedNodeIndex = selectedNodeIds
    .map((nodeId) =>
      nodes.findIndex((node) => !node.isSubgraph && node.id === nodeId),
    )
    .find((index) => index !== -1);

  const styles: Record<string, string> = {};
  nodes.forEach((node, index) => {
    if (isNodeClickable(node.id)) {
      styles[nodeVariable(index, "cursor")] = "pointer";
      styles[nodeVariable(index, "hover-filter")] = "brightness(0.95)";
    }

    const isSelected = node.isSubgraph
      ? selectedNodeIds.includes(node.id)
      : index === selectedNodeIndex;
    if (isSelected) {
      styles[nodeVariable(index, "extra-stroke-width")] =
        SELECTED_EXTRA_STROKE_WIDTH;
    }
  });

  return styles as CSSProperties;
};

export const getMermaidNodeIndex = (
  target: EventTarget,
  container: Element,
): number | null => {
  if (!(target instanceof Element)) return null;
  const element = target.closest(`[${NODE_INDEX_ATTRIBUTE}]`);
  if (!element || !container.contains(element)) return null;
  return Number(element.getAttribute(NODE_INDEX_ATTRIBUTE));
};

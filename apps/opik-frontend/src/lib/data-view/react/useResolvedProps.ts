"use client";

import { useSourceData } from "./DataViewProvider";
import { resolveProps } from "../core/path";
import type { ViewNode } from "../core/types";

/**
 * Hook to resolve dynamic props against source data.
 * Components call this to get resolved values from element.props.
 *
 * @example
 * function MyWidget({ element, children }: ComponentRenderProps) {
 *   const props = useResolvedProps<MyWidgetProps>(element);
 *   return <div>{props.title}</div>;
 * }
 */
export function useResolvedProps<T = Record<string, unknown>>(
  element: ViewNode,
): T {
  const source = useSourceData();
  return resolveProps(element.props, source) as T;
}

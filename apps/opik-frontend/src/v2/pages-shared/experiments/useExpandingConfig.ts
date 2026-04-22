import { useMemo, useState, useCallback, useRef } from "react";
import { ExpandedState } from "@tanstack/react-table";
import isFunction from "lodash/isFunction";

import { Groups } from "@/types/groups";
import { Updater } from "@/types/shared";
import { GROUP_ID_SEPARATOR } from "@/constants/groups";

const getRowDepth = (rowId: string): number =>
  rowId.split(GROUP_ID_SEPARATOR).length - 1;

const getExpandedRowIds = (expandedState: ExpandedState): string[] =>
  Object.keys(expandedState).filter(
    (key) => expandedState[key as keyof ExpandedState],
  );

export type UseExpandingConfigProps = {
  groups?: Groups;
  maxExpandedDeepestGroups?: number;
};

export const useExpandingConfig = ({
  groups,
  maxExpandedDeepestGroups = Number.MAX_SAFE_INTEGER,
}: UseExpandingConfigProps) => {
  const [expanded, setExpanded] = useState<ExpandedState>({});
  const deepestOrderRef = useRef<string[]>([]);

  const maxDepth = groups?.length ?? 0;
  const hasLimit =
    maxExpandedDeepestGroups < Number.MAX_SAFE_INTEGER && maxDepth > 0;

  const handleSetExpanded = useCallback(
    (updaterOrValue: Updater<ExpandedState>) => {
      setExpanded((currentExpanded) => {
        const newExpanded = isFunction(updaterOrValue)
          ? updaterOrValue(currentExpanded)
          : updaterOrValue;

        if (!hasLimit) return newExpanded;

        const expandedRowIds = getExpandedRowIds(newExpanded);
        const deepestRows = expandedRowIds.filter(
          (id) => getRowDepth(id) === maxDepth - 1,
        );

        const updatedOrder = [
          ...deepestOrderRef.current.filter((id) => deepestRows.includes(id)),
          ...deepestRows.filter((id) => !deepestOrderRef.current.includes(id)),
        ];

        const limitedDeepestRows = updatedOrder.slice(
          -maxExpandedDeepestGroups,
        );
        deepestOrderRef.current = limitedDeepestRows;

        return expandedRowIds.reduce<Record<string, boolean>>((acc, id) => {
          if (getRowDepth(id) === maxDepth - 1) {
            acc[id] = limitedDeepestRows.includes(id);
          } else {
            acc[id] = true;
          }
          return acc;
        }, {});
      });
    },
    [hasLimit, maxDepth, maxExpandedDeepestGroups],
  );

  return useMemo(
    () => ({
      autoResetExpanded: false,
      expanded,
      setExpanded: handleSetExpanded,
    }),
    [expanded, handleSetExpanded],
  );
};

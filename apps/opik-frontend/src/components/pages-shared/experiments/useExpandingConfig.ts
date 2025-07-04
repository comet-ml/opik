import { useEffect, useMemo, useRef, useState } from "react";
import { ExpandedState } from "@tanstack/react-table";

import { GROUPING_COLUMN } from "@/constants/grouping";

export type UseExpandingConfigProps = {
  groupIds: string[];
};

export const useExpandingConfig = ({ groupIds }: UseExpandingConfigProps) => {
  const openGroupsRef = useRef<Record<string, boolean>>({});
  const [expanded, setExpanded] = useState<ExpandedState>({});

  useEffect(() => {
    const updateForExpandedState: Record<string, boolean> = {};
    groupIds.forEach((groupId) => {
      const id = `${GROUPING_COLUMN}:${groupId}`;
      // Always set dataset groups to be expanded by default
      if (!openGroupsRef.current[id]) {
        openGroupsRef.current[id] = true;
      }
      // Ensure all groups are expanded in the state
      updateForExpandedState[id] = true;
    });

    if (Object.keys(updateForExpandedState).length) {
      setExpanded((state) => {
        if (state === true) return state;
        return {
          ...state,
          ...updateForExpandedState,
        };
      });
    }
  }, [groupIds]);

  return useMemo(
    () => ({
      autoResetExpanded: false,
      expanded,
      setExpanded,
    }),
    [expanded, setExpanded],
  );
};

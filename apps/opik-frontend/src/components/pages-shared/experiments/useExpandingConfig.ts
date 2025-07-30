import { useMemo, useState } from "react";
import { ExpandedState } from "@tanstack/react-table";
import { Groups } from "@/types/groups";

export type UseExpandingConfigProps = {
  groups?: Groups;
};

export const useExpandingConfig = ({ groups }: UseExpandingConfigProps) => {
  const [expanded, setExpanded] = useState<ExpandedState>({});

  return useMemo(
    () => ({
      autoResetExpanded: false,
      expanded,
      setExpanded,
    }),
    [expanded, setExpanded],
  );
};

import { useEffect, useRef } from "react";

import { Groups, FlattenGroup } from "@/types/groups";
import { generateAutoExpandMap } from "@/lib/groups";
import { OnChangeFn } from "@/types/shared";
import { ExpandedState } from "@tanstack/react-table";

export type UseExperimentsAutoExpandingLogicProps = {
  groups: Groups;
  flattenGroups: FlattenGroup[];
  isPending: boolean;
  isPlaceholderData: boolean;
  maxExpandedDeepestGroups: number;
  setExpanded: OnChangeFn<ExpandedState>;
};

export const useExperimentsAutoExpandingLogic = ({
  groups,
  flattenGroups,
  isPending,
  isPlaceholderData,
  maxExpandedDeepestGroups,
  setExpanded,
}: UseExperimentsAutoExpandingLogicProps) => {
  const previousGroupsFingerPrintRef = useRef("");

  useEffect(() => {
    const groupsFingerprint = groups.map((g) => `${g.key}-${g.key}`).join(",");
    const hasGroupsChanged =
      previousGroupsFingerPrintRef.current !== groupsFingerprint;

    if (
      Boolean(groups.length) &&
      Boolean(flattenGroups.length) &&
      hasGroupsChanged &&
      !isPending &&
      !isPlaceholderData
    ) {
      const newExpandedMap = generateAutoExpandMap(
        flattenGroups,
        maxExpandedDeepestGroups,
      );
      setExpanded(newExpandedMap);
      previousGroupsFingerPrintRef.current = groupsFingerprint;
    }
  }, [
    groups,
    flattenGroups,
    isPending,
    maxExpandedDeepestGroups,
    setExpanded,
    isPlaceholderData,
  ]);
};

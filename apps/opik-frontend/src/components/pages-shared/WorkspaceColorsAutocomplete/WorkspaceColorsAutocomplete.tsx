import React, { useMemo } from "react";
import uniq from "lodash/uniq";

import Autocomplete from "@/components/shared/Autocomplete/Autocomplete";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useTracesFeedbackScoresNames from "@/api/traces/useTracesFeedbackScoresNames";
import useAppStore from "@/store/AppStore";

type WorkspaceColorsAutocompleteProps = {
  value: string;
  onValueChange: (value: string) => void;
  excludeNames?: string[];
  placeholder?: string;
  hasError?: boolean;
  className?: string;
};

const WorkspaceColorsAutocomplete: React.FC<
  WorkspaceColorsAutocompleteProps
> = ({
  value,
  onValueChange,
  excludeNames = [],
  placeholder = "Search feedback score name...",
  hasError,
  className,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: definitionsData, isPending: isDefinitionsPending } =
    useFeedbackDefinitionsList({
      workspaceName,
      page: 1,
      size: 1000,
    });

  const { data: scoresData, isPending: isScoresPending } =
    useTracesFeedbackScoresNames({});

  const items = useMemo(() => {
    const definitionNames = (definitionsData?.content ?? []).map((d) => d.name);
    const scoreNames = (scoresData?.scores ?? []).map((s) => s.name);

    const excludeSet = new Set(excludeNames.map((n) => n.toLowerCase()));

    return uniq([...definitionNames, ...scoreNames])
      .filter((name) => !excludeSet.has(name.toLowerCase()))
      .sort();
  }, [definitionsData, scoresData, excludeNames]);

  return (
    <Autocomplete
      value={value}
      onValueChange={onValueChange}
      items={items}
      isLoading={isDefinitionsPending || isScoresPending}
      placeholder={placeholder}
      hasError={hasError}
      className={className}
    />
  );
};

export default WorkspaceColorsAutocomplete;

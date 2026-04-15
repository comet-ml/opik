import React, { useCallback, useMemo, useState } from "react";
import { FileTerminal, Info, XCircle } from "lucide-react";
import { QueryFunctionContext, useQueries } from "@tanstack/react-query";

import { Button } from "@/ui/button";
import LoadableSelectBox from "@/shared/LoadableSelectBox/LoadableSelectBox";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import useConfigHistoryListInfinite from "@/api/agent-configs/useConfigHistoryListInfinite";
import useAgentConfigById from "@/api/agent-configs/useAgentConfigById";
import { BlueprintValue, BlueprintValueType } from "@/types/agent-configs";
import { BlueprintPromptRef } from "@/types/playground";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import { getPromptByCommit } from "@/api/prompts/usePromptByCommit";

interface BlueprintPromptsSelectBoxProps {
  projectId: string;
  value?: BlueprintPromptRef;
  onValueChange: (value: BlueprintPromptRef) => void;
  onClear?: () => void;
  onOpenChange?: (open: boolean) => void;
  hasUnsavedChanges?: boolean;
  disabled?: boolean;
  filterByTemplateStructure?: PROMPT_TEMPLATE_STRUCTURE;
}

interface LoadedDisplayProps {
  promptKey: string;
  hasUnsavedChanges: boolean;
  onClear?: () => void;
}

const LoadedDisplay: React.FC<LoadedDisplayProps> = ({
  promptKey,
  hasUnsavedChanges,
  onClear,
}) => (
  <div className="flex min-w-0 items-center px-1">
    <TooltipWrapper content={hasUnsavedChanges ? "Unsaved changes" : promptKey}>
      <div className="flex min-w-0 items-center gap-1">
        <FileTerminal className="size-3.5 shrink-0 text-library-loaded" />
        <span className="comet-body-xs-accented truncate text-light-slate">
          {promptKey}
        </span>
        {hasUnsavedChanges && (
          <span className="mb-auto size-1 shrink-0 rounded-full bg-warning" />
        )}
      </div>
    </TooltipWrapper>
    {onClear && (
      <TooltipWrapper content="Detach prompt">
        <Button
          variant="minimal"
          size="icon-xs"
          className="shrink-0"
          onClick={onClear}
        >
          <XCircle />
        </Button>
      </TooltipWrapper>
    )}
  </div>
);

const PROMPT_COMMIT_STALE_TIME = 5 * 60 * 1000;

const useFilteredPromptValues = (
  promptValues: BlueprintValue[],
  filter?: PROMPT_TEMPLATE_STRUCTURE,
) => {
  const queries = useQueries({
    queries: filter
      ? promptValues.map((v) => ({
          queryKey: ["prompt-by-commit", { commitId: v.value }],
          queryFn: (context: QueryFunctionContext) =>
            getPromptByCommit(context, { commitId: v.value }),
          staleTime: PROMPT_COMMIT_STALE_TIME,
          enabled: !!v.value,
        }))
      : [],
  });

  return useMemo(() => {
    if (!filter) return { values: promptValues, isResolving: false };

    const isResolving = queries.some((q) => q.isLoading);
    if (isResolving) return { values: [], isResolving: true };

    const filtered = promptValues.filter((_, i) => {
      const structure = queries[i]?.data?.template_structure;
      return structure === filter;
    });
    return { values: filtered, isResolving: false };
  }, [filter, promptValues, queries]);
};

const BlueprintPromptsSelectBox: React.FC<BlueprintPromptsSelectBoxProps> = ({
  projectId,
  value,
  onValueChange,
  onClear,
  onOpenChange,
  hasUnsavedChanges = false,
  disabled = false,
  filterByTemplateStructure,
}) => {
  const [open, setOpen] = useState(false);

  const handleOpenChange = useCallback(
    (next: boolean) => {
      setOpen(next);
      onOpenChange?.(next);
    },
    [onOpenChange],
  );

  const { data: history, isLoading: isLoadingHistory } =
    useConfigHistoryListInfinite({ projectId });
  const latestBlueprint = history?.pages?.[0]?.content?.[0];
  const latestBlueprintId = latestBlueprint?.id;
  const latestBlueprintName = latestBlueprint?.name;

  const { data: blueprint, isLoading: isLoadingBlueprint } = useAgentConfigById(
    { blueprintId: latestBlueprintId ?? "" },
  );

  const allPromptValues = useMemo(
    () =>
      blueprint?.values?.filter((v) => v.type === BlueprintValueType.PROMPT) ??
      [],
    [blueprint],
  );

  const { values: promptValues, isResolving } = useFilteredPromptValues(
    allPromptValues,
    filterByTemplateStructure,
  );

  const isLoading = isLoadingHistory || isLoadingBlueprint || isResolving;

  const options = useMemo(
    () => promptValues.map((v) => ({ label: v.key, value: v.key })),
    [promptValues],
  );

  const handleChange = useCallback(
    (key: string) => {
      const match = promptValues.find((v) => v.key === key);
      if (!match || !latestBlueprintId) return;
      onValueChange({
        blueprintId: latestBlueprintId,
        key: match.key,
        commitId: match.value,
      });
    },
    [latestBlueprintId, promptValues, onValueChange],
  );

  if (value) {
    return (
      <LoadedDisplay
        promptKey={value.key}
        hasUnsavedChanges={hasUnsavedChanges}
        onClear={onClear}
      />
    );
  }

  const isDisabled = disabled || (!isLoading && options.length === 0);
  const triggerTooltip = isDisabled
    ? "No agent configuration prompts found for this project"
    : "Load prompt from agent configuration";

  return (
    <LoadableSelectBox
      options={options}
      searchPlaceholder="Search prompt"
      onChange={handleChange}
      open={open}
      onOpenChange={handleOpenChange}
      isLoading={isLoading}
      optionsCount={options.length}
      trigger={
        <div>
          <TooltipWrapper content={triggerTooltip}>
            <Button variant="minimal" size="icon-sm" disabled={isDisabled}>
              <FileTerminal />
            </Button>
          </TooltipWrapper>
        </div>
      }
      minWidth={360}
      disabled={isDisabled}
      actionPanel={
        <div className="comet-body-xs flex items-start gap-1.5 border-t border-border px-3 py-2 text-light-slate">
          <Info className="mt-0.5 size-3 shrink-0" />
          <span>
            Prompts are loaded from the latest agent configuration
            {latestBlueprintName ? ` (${latestBlueprintName})` : ""}.
          </span>
        </div>
      }
    />
  );
};

export default BlueprintPromptsSelectBox;

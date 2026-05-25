import React, { useCallback, useMemo, useState } from "react";
import { FileTerminal, Plus } from "lucide-react";
import isFunction from "lodash/isFunction";

import { cn } from "@/lib/utils";
import { Button } from "@/ui/button";
import { ListAction } from "@/ui/list-action";
import { Separator } from "@/ui/separator";
import LoadableSelectBox from "@/shared/LoadableSelectBox/LoadableSelectBox";
import SelectBoxClearWrapper from "@/shared/SelectBoxClearWrapper/SelectBoxClearWrapper";
import useProjectPromptsList from "@/api/prompts/useProjectPromptsList";
import usePromptById from "@/api/prompts/usePromptById";
import { Prompt, PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import useDeepMemo from "@/hooks/useDeepMemo";
import usePromptVersionLabel from "@/hooks/usePromptVersionLabel";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import PromptLibraryMenu from "@/v2/pages-shared/llm/PromptLibraryMenu/PromptLibraryMenu";
import LoadedPromptDisplay from "@/v2/pages-shared/llm/LoadedPromptDisplay/LoadedPromptDisplay";

const DEFAULT_LOADED_PROMPTS = 1000;
const MAX_LOADED_PROMPTS = 10000;
const NEW_PROMPT_VALUE = "new-prompt";

interface PromptsSelectBoxProps {
  projectId: string;
  value?: string;
  onValueChange: (value?: string) => void;
  onClear?: () => void;
  onOpenChange?: (value: boolean) => void;
  clearable?: boolean;
  refetchOnMount?: boolean;
  asNewOption?: boolean;
  filterByTemplateStructure?: PROMPT_TEMPLATE_STRUCTURE;
  disabled?: boolean;
  hasUnsavedChanges?: boolean;
  promptName?: string;
  loadedVersionId?: string;
  compact?: boolean;
}

const PromptsSelectBox: React.FC<PromptsSelectBoxProps> = ({
  projectId,
  value,
  onValueChange,
  onClear,
  onOpenChange,
  clearable = true,
  refetchOnMount = false,
  asNewOption = false,
  filterByTemplateStructure,
  disabled = false,
  hasUnsavedChanges = false,
  promptName,
  loadedVersionId,
  compact = false,
}) => {
  const [open, setOpen] = useState(false);
  const [isLoadedMore, setIsLoadedMore] = useState(false);
  const isClearable = clearable && Boolean(value);

  const { data: promptsData, isLoading: isLoadingPrompts } =
    useProjectPromptsList(
      {
        projectId,
        page: 1,
        size: !isLoadedMore ? DEFAULT_LOADED_PROMPTS : MAX_LOADED_PROMPTS,
      },
      {
        refetchOnMount,
      },
    );

  const prompts = useDeepMemo(
    () => promptsData?.content ?? [],
    [promptsData?.content],
  );

  const promptsOptions = useMemo(() => {
    const filteredPrompts = filterByTemplateStructure
      ? prompts.filter(
          (p) => p.template_structure === filterByTemplateStructure,
        )
      : prompts;

    return filteredPrompts.map(({ name, id }) => ({
      label: name,
      value: id,
    }));
  }, [prompts, filterByTemplateStructure]);

  const promptsTotal = promptsData?.total ?? 0;

  const loadMoreHandler = useCallback(() => setIsLoadedMore(true), []);

  const onOpenChangeHandler = useCallback(
    (open: boolean) => {
      if (isFunction(onOpenChange)) {
        onOpenChange(open);
      }

      setOpen(open);
    },
    [onOpenChange],
  );

  const placeholder = useMemo(
    () =>
      asNewOption ? (
        <div className="flex w-full items-center text-foreground">
          <span className="truncate">Save as a new prompt</span>
        </div>
      ) : (
        <div className="flex w-full items-center text-light-slate">
          <FileTerminal className="mr-2 size-4" />
          <span className="truncate font-normal">
            {filterByTemplateStructure === PROMPT_TEMPLATE_STRUCTURE.CHAT
              ? "Load chat prompt"
              : "Load a prompt"}
          </span>
        </div>
      ),
    [asNewOption, filterByTemplateStructure],
  );

  let searchPlaceholder = "Search";
  if (filterByTemplateStructure === PROMPT_TEMPLATE_STRUCTURE.CHAT) {
    searchPlaceholder = "Search chat prompt";
  } else if (filterByTemplateStructure === PROMPT_TEMPLATE_STRUCTURE.TEXT) {
    searchPlaceholder = "Search text prompt";
  }

  const actionPanel = useMemo(() => {
    return asNewOption ? (
      <>
        <Separator className="my-1" />
        <ListAction
          onClick={() => {
            onValueChange(undefined);
            setOpen(false);
          }}
        >
          <Plus className="size-3.5 shrink-0" />
          Save as new
        </ListAction>
      </>
    ) : undefined;
  }, [asNewOption, onValueChange]);

  if (compact) {
    if (value) {
      const promptFromList = prompts.find((p) => p.id === value);
      const displayName = promptName ?? promptFromList?.name ?? "Loaded prompt";

      return (
        <CompactLoadedPrompt
          promptId={value}
          displayName={displayName}
          prompt={promptFromList}
          versionId={loadedVersionId}
          hasUnsavedChanges={hasUnsavedChanges}
          onClear={onClear}
        />
      );
    }

    return (
      <PromptLibraryMenu
        projectId={projectId}
        filterByTemplateStructure={filterByTemplateStructure}
        onSelect={({ promptId }) => onValueChange(promptId)}
        onOpenChange={onOpenChangeHandler}
        trigger={
          <div>
            <TooltipWrapper content="Load prompt">
              <Button variant="minimal" size="icon-sm" disabled={disabled}>
                <FileTerminal />
              </Button>
            </TooltipWrapper>
          </div>
        }
      />
    );
  }

  return (
    <SelectBoxClearWrapper
      isClearable={isClearable}
      onClear={() => onValueChange(undefined)}
      disabled={disabled}
      clearTooltip="Remove prompt selection"
      buttonSize="icon-sm"
    >
      <LoadableSelectBox
        options={promptsOptions}
        value={value ?? (asNewOption ? NEW_PROMPT_VALUE : "")}
        placeholder={placeholder}
        searchPlaceholder={searchPlaceholder}
        onChange={onValueChange}
        open={open}
        onOpenChange={onOpenChangeHandler}
        buttonSize="sm"
        onLoadMore={
          promptsTotal > DEFAULT_LOADED_PROMPTS && !isLoadedMore
            ? loadMoreHandler
            : undefined
        }
        isLoading={isLoadingPrompts}
        optionsCount={DEFAULT_LOADED_PROMPTS}
        buttonClassName={cn("flex-auto max-w-full min-w-1", {
          "rounded-r-none": isClearable,
        })}
        renderTitle={(option) => {
          return (
            <div className="flex w-full min-w-1 flex-nowrap items-center text-foreground">
              <FileTerminal className="mr-2 size-4 shrink-0" />
              <span className="truncate">{option.label}</span>
            </div>
          );
        }}
        actionPanel={actionPanel}
        minWidth={540}
        showTooltip
        disabled={disabled}
      />
    </SelectBoxClearWrapper>
  );
};

type CompactLoadedPromptProps = {
  promptId: string;
  displayName: string;
  prompt?: Prompt;
  versionId?: string;
  hasUnsavedChanges?: boolean;
  onClear?: () => void;
};

const CompactLoadedPrompt: React.FC<CompactLoadedPromptProps> = ({
  promptId,
  displayName,
  prompt,
  versionId,
  hasUnsavedChanges,
  onClear,
}) => {
  const { data: fetched } = usePromptById(
    { promptId },
    { enabled: !!promptId && !prompt },
  );
  const data = prompt ?? fetched;
  const versionLabel = usePromptVersionLabel(
    promptId,
    versionId,
    data?.version_count,
  );

  return (
    <LoadedPromptDisplay
      name={displayName}
      templateStructure={data?.template_structure}
      versionLabel={versionLabel}
      versionTags={data?.latest_version?.tags}
      hasUnsavedChanges={hasUnsavedChanges}
      onClear={onClear}
    />
  );
};

export default PromptsSelectBox;

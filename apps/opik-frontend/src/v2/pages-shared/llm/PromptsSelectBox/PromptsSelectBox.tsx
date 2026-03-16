import React, { useCallback, useMemo, useState } from "react";
import { FileTerminal, Plus, XCircle } from "lucide-react";
import isFunction from "lodash/isFunction";

import { cn } from "@/lib/utils";
import { Button } from "@/ui/button";
import { ListAction } from "@/ui/list-action";
import { Separator } from "@/ui/separator";
import LoadableSelectBox from "@/shared/LoadableSelectBox/LoadableSelectBox";
import SelectBoxClearWrapper from "@/shared/SelectBoxClearWrapper/SelectBoxClearWrapper";
import useProjectPromptsList from "@/api/prompts/useProjectPromptsList";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import useDeepMemo from "@/hooks/useDeepMemo";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

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
      const displayName =
        promptName ??
        promptsOptions.find((o) => o.value === value)?.label ??
        "Loaded prompt";

      return (
        <div className="flex max-w-44 items-center gap-1 px-2">
          <TooltipWrapper
            content={hasUnsavedChanges ? "Unsaved changes" : displayName}
          >
            <div className="flex min-w-0 items-center gap-1">
              <FileTerminal className="size-3.5 shrink-0 text-[#b8e54a]" />
              <span className="comet-body-xs-accented truncate">
                {displayName}
              </span>
              {hasUnsavedChanges && (
                <span className="mb-auto size-1 shrink-0 rounded-full bg-warning" />
              )}
            </div>
          </TooltipWrapper>
          {onClear && (
            <TooltipWrapper content="Detach loaded prompt">
              <Button
                variant="ghost"
                size="icon-xs"
                className="ml-0.5 shrink-0 text-muted-slate hover:text-primary-hover"
                onClick={onClear}
              >
                <XCircle />
              </Button>
            </TooltipWrapper>
          )}
        </div>
      );
    }

    return (
      <LoadableSelectBox
        options={promptsOptions}
        searchPlaceholder={searchPlaceholder}
        onChange={onValueChange}
        open={open}
        onOpenChange={onOpenChangeHandler}
        onLoadMore={
          promptsTotal > DEFAULT_LOADED_PROMPTS && !isLoadedMore
            ? loadMoreHandler
            : undefined
        }
        isLoading={isLoadingPrompts}
        optionsCount={DEFAULT_LOADED_PROMPTS}
        trigger={
          <div>
            <TooltipWrapper content="Load chat prompt">
              <Button
                variant="ghost"
                size="icon-sm"
                className="text-inherit"
                disabled={disabled}
              >
                <FileTerminal />
              </Button>
            </TooltipWrapper>
          </div>
        }
        actionPanel={actionPanel}
        minWidth={540}
        disabled={disabled}
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

export default PromptsSelectBox;

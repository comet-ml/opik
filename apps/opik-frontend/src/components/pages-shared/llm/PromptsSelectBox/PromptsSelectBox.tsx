import React, { useCallback, useMemo, useState } from "react";
import { FileTerminal, Plus } from "lucide-react";
import isFunction from "lodash/isFunction";

import useAppStore from "@/store/AppStore";
import { cn } from "@/lib/utils";
import { ListAction } from "@/components/ui/list-action";
import { Separator } from "@/components/ui/separator";
import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import SelectBoxClearWrapper from "@/components/shared/SelectBoxClearWrapper/SelectBoxClearWrapper";
import usePromptsList from "@/api/prompts/usePromptsList";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import useDeepMemo from "@/hooks/useDeepMemo";

const DEFAULT_LOADED_PROMPTS = 1000;
const MAX_LOADED_PROMPTS = 10000;
const NEW_PROMPT_VALUE = "new-prompt";

interface PromptsSelectBoxProps {
  value?: string;
  onValueChange: (value?: string) => void;
  onOpenChange?: (value: boolean) => void;
  clearable?: boolean;
  refetchOnMount?: boolean;
  asNewOption?: boolean;
  filterByTemplateStructure?: PROMPT_TEMPLATE_STRUCTURE;
  disabled?: boolean;
}

const PromptsSelectBox: React.FC<PromptsSelectBoxProps> = ({
  value,
  onValueChange,
  onOpenChange,
  clearable = true,
  refetchOnMount = false,
  asNewOption = false,
  filterByTemplateStructure,
  disabled = false,
}) => {
  const [open, setOpen] = useState(false);
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [isLoadedMore, setIsLoadedMore] = useState(false);
  const isClearable = clearable && Boolean(value);

  const { data: promptsData, isLoading: isLoadingPrompts } = usePromptsList(
    {
      workspaceName,
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

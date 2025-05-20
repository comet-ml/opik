import React, { useCallback, useMemo, useState } from "react";
import { Database, FileTerminal, Plus, X } from "lucide-react";
import isFunction from "lodash/isFunction";

import useAppStore from "@/store/AppStore";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import usePromptsList from "@/api/prompts/usePromptsList";

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
}

const PromptsSelectBox: React.FC<PromptsSelectBoxProps> = ({
  value,
  onValueChange,
  onOpenChange,
  clearable = true,
  refetchOnMount = false,
  asNewOption = false,
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

  const prompts = useMemo(
    () => promptsData?.content ?? [],
    [promptsData?.content],
  );

  const promptsOptions = useMemo(() => {
    return prompts.map(({ name, id }) => ({
      label: name,
      value: id,
    }));
  }, [prompts]);

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
          <Plus className="mr-2 size-4 shrink-0" />
          <span className="truncate">Save as a new prompt</span>
        </div>
      ) : (
        <div className="flex w-full items-center text-light-slate">
          <FileTerminal className="mr-2 size-4" />
          <span className="truncate font-normal">Load a prompt</span>
        </div>
      ),
    [asNewOption],
  );

  const actionPanel = useMemo(() => {
    return asNewOption ? (
      <div className="px-0.5">
        <Separator className="my-1" />
        <button
          className="flex h-10 w-full flex-nowrap items-center px-4 text-foreground"
          onClick={() => {
            onValueChange(undefined);
            setOpen(false);
          }}
        >
          <Plus className="mr-2 size-4 shrink-0" />
          <span>Save as a new prompt</span>
        </button>
      </div>
    ) : undefined;
  }, [asNewOption, onValueChange]);

  return (
    <>
      <LoadableSelectBox
        options={promptsOptions}
        value={value ?? (asNewOption ? NEW_PROMPT_VALUE : "")}
        placeholder={placeholder}
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
              <Database className="mr-2 size-4 shrink-0" />
              <span className="truncate">{option.label}</span>
            </div>
          );
        }}
        actionPanel={actionPanel}
        minWidth={540}
      />

      {isClearable && (
        <TooltipWrapper content="Remove prompt selection">
          <Button
            variant="outline"
            size="icon-sm"
            className="shrink-0 rounded-l-none border-l-0"
            onClick={() => onValueChange(undefined)}
          >
            <X className="text-light-slate" />
          </Button>
        </TooltipWrapper>
      )}
    </>
  );
};

export default PromptsSelectBox;

import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { RotateCcw, Save } from "lucide-react";
import isUndefined from "lodash/isUndefined";

import { OnChangeFn } from "@/types/shared";
import { LLMMessage } from "@/types/llm";
import { Separator } from "@/components/ui/separator";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import usePromptById from "@/api/prompts/usePromptById";
import PromptsSelectBox from "@/components/pages-shared/llm/PromptsSelectBox/PromptsSelectBox";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import AddNewPromptVersionDialog from "@/components/pages-shared/llm/LLMPromptMessages/AddNewPromptVersionDialog";

type ConfirmType = "load" | "reset" | "save";

type LLMPromptLibraryActionsProps = {
  message: LLMMessage;
  onChangeMessage: (changes: Partial<LLMMessage>) => void;
  setIsLoading: OnChangeFn<boolean>;
  setIsHoldActionsVisible: OnChangeFn<boolean>;
};

const LLMPromptMessageActions: React.FC<LLMPromptLibraryActionsProps> = ({
  message,
  onChangeMessage,
  setIsLoading,
  setIsHoldActionsVisible,
}) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean | ConfirmType>(false);
  const selectedPromptIdRef = useRef<string | undefined>();
  const tempPromptIdRef = useRef<string | undefined>();

  const { promptId, content } = message;
  const { data: promptData } = usePromptById(
    { promptId: promptId! },
    { enabled: !!promptId },
  );

  const handleUpdateExternalPromptId = useCallback(
    (selectedPromptId?: string) => {
      if (selectedPromptId) {
        selectedPromptIdRef.current = selectedPromptId;
        setIsLoading(true);
      }

      onChangeMessage({
        promptId: selectedPromptId,
      });
    },
    [onChangeMessage, setIsLoading],
  );

  const resetHandler = useCallback(() => {
    onChangeMessage({
      content: promptData!.latest_version?.template ?? "",
    });
  }, [onChangeMessage, promptData]);

  const onSaveHandler = useCallback(
    (id: string) => {
      onChangeMessage({
        promptId: id,
      });
    },
    [onChangeMessage],
  );

  const resetDisabled =
    !promptId ||
    promptData?.id !== promptId ||
    (promptData?.id === promptId &&
      message.content === promptData?.latest_version?.template);

  const saveDisabled = message.content === "";

  const confirmConfig = useMemo(() => {
    const isReset = open === "reset";

    return {
      onConfirm: () => {
        isReset
          ? resetHandler()
          : handleUpdateExternalPromptId(tempPromptIdRef.current);
      },
      title: isReset ? "Reset prompt" : "Load prompt",
      description: isReset
        ? "Resetting the prompt will discard all unsaved changes. This action is irreversible. Do you want to proceed?"
        : "You have unsaved changes in your message field. Loading a new prompt will overwrite them with the prompt’s content. This action cannot be undone.",
      confirmText: isReset ? "Reset prompt" : "Load prompt",
    };
  }, [handleUpdateExternalPromptId, open, resetHandler]);

  useEffect(() => {
    if (
      selectedPromptIdRef.current &&
      selectedPromptIdRef.current === promptId &&
      selectedPromptIdRef.current === promptData?.id
    ) {
      selectedPromptIdRef.current = undefined;
      onChangeMessage({
        content: promptData!.latest_version?.template ?? "",
      });
      setIsLoading(false);
    }
  }, [onChangeMessage, promptData, promptId, setIsLoading]);

  return (
    <div className="flex h-full flex-1 cursor-default flex-nowrap items-center justify-start gap-2">
      <div className="flex h-full min-w-40 max-w-60 flex-auto flex-nowrap">
        <PromptsSelectBox
          value={promptId}
          onValueChange={(id) => {
            if (id !== promptId) {
              if (content === "" || isUndefined(id)) {
                handleUpdateExternalPromptId(id);
              } else {
                setOpen("load");
                resetKeyRef.current = resetKeyRef.current + 1;
                tempPromptIdRef.current = id;
              }
            }
          }}
          onOpenChange={setIsHoldActionsVisible}
        />
      </div>
      <TooltipWrapper content="Discard changes">
        <Button
          variant="outline"
          size="icon-sm"
          disabled={resetDisabled}
          onClick={() => {
            resetKeyRef.current = resetKeyRef.current + 1;
            setOpen("reset");
          }}
        >
          <RotateCcw />
        </Button>
      </TooltipWrapper>

      <TooltipWrapper content="Save changes">
        <Button
          variant="outline"
          size="icon-sm"
          disabled={saveDisabled}
          onClick={() => {
            resetKeyRef.current = resetKeyRef.current + 1;
            setOpen("save");
          }}
        >
          <Save />
        </Button>
      </TooltipWrapper>

      <Separator orientation="vertical" className="ml-1 mr-2 h-6" />

      <ConfirmDialog
        key={`confirm-${resetKeyRef.current}`}
        open={open === "load" || open === "reset"}
        setOpen={setOpen}
        {...confirmConfig}
      />
      <AddNewPromptVersionDialog
        key={`save-${resetKeyRef.current}`}
        open={open === "save"}
        setOpen={setOpen}
        prompt={promptData}
        template={content}
        onSave={onSaveHandler}
      />
    </div>
  );
};

export default LLMPromptMessageActions;

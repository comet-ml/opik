import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import useLocalStorageState from "use-local-storage-state";
import { RotateCcw, Save, Wand2 } from "lucide-react";
import isUndefined from "lodash/isUndefined";

import { OnChangeFn } from "@/types/shared";
import { LLMMessage } from "@/types/llm";
import { PromptVersion } from "@/types/prompts";
import { PLAYGROUND_SELECTED_DATASET_KEY } from "@/constants/llm";
import { Separator } from "@/components/ui/separator";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import usePromptById from "@/api/prompts/usePromptById";
import PromptsSelectBox from "@/components/pages-shared/llm/PromptsSelectBox/PromptsSelectBox";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import AddNewPromptVersionDialog from "@/components/pages-shared/llm/LLMPromptMessages/AddNewPromptVersionDialog";
import PromptImprovementWizard from "@/components/shared/PromptImprovementWizard/PromptImprovementWizard";
import { LLMPromptConfigsType, PROVIDER_TYPE } from "@/types/providers";
import { useToast } from "@/components/ui/use-toast";

type ConfirmType = "load" | "reset" | "save";

export interface ImprovePromptConfig {
  enabled: boolean;
  model: string;
  provider: PROVIDER_TYPE | "";
  configs: LLMPromptConfigsType;
  workspaceName: string;
  onAccept: (messageId: string, improvedContent: string) => void;
}

type LLMPromptLibraryActionsProps = {
  message: LLMMessage;
  onChangeMessage: (changes: Partial<LLMMessage>) => void;
  setIsLoading: OnChangeFn<boolean>;
  setIsHoldActionsVisible: OnChangeFn<boolean>;
  improvePromptConfig?: ImprovePromptConfig;
};

const LLMPromptMessageActions: React.FC<LLMPromptLibraryActionsProps> = ({
  message,
  onChangeMessage,
  setIsLoading,
  setIsHoldActionsVisible,
  improvePromptConfig,
}) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean | ConfirmType>(false);
  const selectedPromptIdRef = useRef<string | undefined>();
  const tempPromptIdRef = useRef<string | undefined>();
  const isPromptSelectBoxOpenedRef = useRef<boolean>(false);
  const isPromptSaveWarningRef = useRef<boolean>(false);
  const [showImproveWizard, setShowImproveWizard] = useState(false);

  const { toast } = useToast();

  const [datasetId] = useLocalStorageState<string | null>(
    PLAYGROUND_SELECTED_DATASET_KEY,
    {
      defaultValue: null,
    },
  );

  const { promptId, content } = message;
  const { data: promptData } = usePromptById(
    { promptId: promptId! },
    { enabled: !!promptId },
  );

  const hasContent = Boolean(content?.trim());
  const showGenerateButton = improvePromptConfig?.enabled && !hasContent;
  const showImproveButton = improvePromptConfig?.enabled && hasContent;
  const hasModel = Boolean(improvePromptConfig?.model?.trim());
  const isPromptButtonDisabled = !hasModel;
  const promptButtonTooltip = !hasModel
    ? "Configure model first"
    : hasContent
      ? "Improve prompt with AI"
      : "Generate prompt with AI";

  const handleOpenWizard = useCallback(() => {
    setShowImproveWizard(true);
  }, []);

  // Auto-open improvement wizard when message autoImprove flag is set
  useEffect(() => {
    if (
      message.autoImprove &&
      improvePromptConfig?.enabled &&
      promptId && // Only trigger for message loaded from prompt library
      hasContent // Only trigger if there's content to improve
    ) {
      // Use a small delay to ensure the component is fully mounted and model is loaded
      const timeoutId = setTimeout(() => {
        // Clear the flag first to prevent other instances from triggering
        onChangeMessage({ autoImprove: false });

        // Validate model and provider are configured
        if (!hasModel) {
          toast({
            title: "Model configuration required",
            description:
              "Please configure a model and provider before improving the prompt. Select a model from the dropdown above.",
          });
          return;
        }

        setShowImproveWizard(true);
      }, 300);

      return () => clearTimeout(timeoutId);
    }
  }, [
    message.autoImprove,
    improvePromptConfig?.enabled,
    promptId,
    hasContent,
    hasModel,
    onChangeMessage,
    toast,
  ]);

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
      promptVersionId: promptData!.latest_version?.id,
    });
  }, [onChangeMessage, promptData]);

  const onSaveHandler = useCallback(
    (version: PromptVersion) => {
      onChangeMessage({
        promptId: version.prompt_id,
        content: version.template ?? "",
        promptVersionId: version.id,
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
  const saveWarning = Boolean(
    !saveDisabled &&
      promptId &&
      promptData?.id === promptId &&
      message.content !== promptData?.latest_version?.template,
  );
  isPromptSaveWarningRef.current = saveWarning;
  const saveTooltip = saveWarning
    ? !datasetId
      ? "This prompt version hasn't been saved"
      : "This prompt version hasn't been saved. Save it to link it to the experiment and make comparisons easier."
    : "Save changes";

  const onPromptSelectBoxOpenChange = useCallback(
    (open: boolean) => {
      isPromptSelectBoxOpenedRef.current = open;
      setIsHoldActionsVisible(
        isPromptSelectBoxOpenedRef.current || isPromptSaveWarningRef.current,
      );
    },
    [setIsHoldActionsVisible],
  );

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
        ? "Resetting the prompt will discard all unsaved changes. This action can’t be undone. Are you sure you want to continue?"
        : "You have unsaved changes in your message field. Loading a new prompt will overwrite them with the prompt’s content. This action cannot be undone.",
      confirmText: isReset ? "Reset prompt" : "Load prompt",
    };
  }, [handleUpdateExternalPromptId, open, resetHandler]);

  // This effect is used to set the visibility of hold actions
  // based on the prompt select box state and save warning
  useEffect(() => {
    setIsHoldActionsVisible(isPromptSelectBoxOpenedRef.current || saveWarning);
  }, [saveWarning, setIsHoldActionsVisible]);

  // This effect is used to set the template and promptVersionId after it is loaded,
  // after it was set in handleUpdateExternalPromptId function
  useEffect(() => {
    if (
      selectedPromptIdRef.current &&
      selectedPromptIdRef.current === promptId &&
      selectedPromptIdRef.current === promptData?.id
    ) {
      selectedPromptIdRef.current = undefined;
      onChangeMessage({
        content: promptData.latest_version?.template ?? "",
        promptVersionId: promptData.latest_version?.id,
      });
      setIsLoading(false);
    }
  }, [onChangeMessage, promptData, promptId, setIsLoading]);

  return (
    <>
      <div className="flex h-full flex-1 cursor-default flex-nowrap items-center justify-start gap-2">
        {showGenerateButton && (
          <TooltipWrapper content={promptButtonTooltip}>
            <span>
              <Button
                variant="outline"
                size="sm"
                onClick={handleOpenWizard}
                type="button"
                disabled={isPromptButtonDisabled}
              >
                <Wand2 className="mr-2 size-4" />
                Generate prompt
              </Button>
            </span>
          </TooltipWrapper>
        )}
        {showImproveButton && (
          <TooltipWrapper content={promptButtonTooltip}>
            <span>
              <Button
                variant="outline"
                size="sm"
                onClick={handleOpenWizard}
                type="button"
                disabled={isPromptButtonDisabled}
              >
                <Wand2 className="mr-2 size-4" />
                Improve prompt
              </Button>
            </span>
          </TooltipWrapper>
        )}
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
            onOpenChange={onPromptSelectBoxOpenChange}
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

        <TooltipWrapper content={saveTooltip}>
          <Button
            variant="outline"
            size="icon-sm"
            disabled={saveDisabled}
            badge={saveWarning}
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
      {improvePromptConfig && (
        <PromptImprovementWizard
          open={showImproveWizard}
          setOpen={setShowImproveWizard}
          id={message.id}
          originalPrompt={content}
          model={improvePromptConfig.model}
          provider={improvePromptConfig.provider}
          configs={improvePromptConfig.configs}
          workspaceName={improvePromptConfig.workspaceName}
          onAccept={improvePromptConfig.onAccept}
        />
      )}
    </>
  );
};

export default LLMPromptMessageActions;

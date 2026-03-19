import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import useLocalStorageState from "use-local-storage-state";
import { Save, Sparkles, Wand2 } from "lucide-react";
import isUndefined from "lodash/isUndefined";
import isEqual from "fast-deep-equal";

import { OnChangeFn } from "@/types/shared";
import { LLMMessage, MessageContent } from "@/types/llm";
import { PromptVersion, PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import { PLAYGROUND_SELECTED_DATASET_VERSION_KEY } from "@/constants/llm";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import usePromptById from "@/api/prompts/usePromptById";
import { useActiveProjectId } from "@/store/AppStore";
import PromptsSelectBox from "@/v2/pages-shared/llm/PromptsSelectBox/PromptsSelectBox";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import AddNewPromptVersionDialog from "@/v2/pages-shared/llm/LLMPromptMessages/AddNewPromptVersionDialog";
import PromptImprovementDialog from "@/v2/pages-shared/llm/PromptImprovementDialog/PromptImprovementDialog";
import {
  LLMPromptConfigsType,
  COMPOSED_PROVIDER_TYPE,
} from "@/types/providers";
import { useToast } from "@/ui/use-toast";
import {
  getTextFromMessageContent,
  convertMessageToMessagesJson,
  parsePromptVersionContent,
  parseChatTemplateToLLMMessages,
} from "@/lib/llm";

type ConfirmType = "load" | "save";

export interface ImprovePromptConfig {
  model: string;
  provider: COMPOSED_PROVIDER_TYPE | "";
  configs: LLMPromptConfigsType;
  workspaceName: string;
  onAccept: (messageId: string, improvedContent: MessageContent) => void;
}

type LLMPromptLibraryActionsProps = {
  message: LLMMessage;
  onChangeMessage: (changes: Partial<LLMMessage>) => void;
  onReplaceWithChatPrompt?: (
    messages: LLMMessage[],
    promptId: string,
    promptVersionId: string,
  ) => void;
  onClearOtherPromptLinks?: () => void;
  setIsLoading: OnChangeFn<boolean>;
  setIsHoldActionsVisible: OnChangeFn<boolean>;
  improvePromptConfig?: ImprovePromptConfig;
};

const LLMPromptMessageActions: React.FC<LLMPromptLibraryActionsProps> = ({
  message,
  onChangeMessage,
  onReplaceWithChatPrompt,
  onClearOtherPromptLinks,
  setIsLoading,
  setIsHoldActionsVisible,
  improvePromptConfig,
}) => {
  const activeProjectId = useActiveProjectId();
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean | ConfirmType>(false);
  const selectedPromptIdRef = useRef<string | undefined>();
  const tempPromptIdRef = useRef<string | undefined>();
  const isPromptSelectBoxOpenedRef = useRef<boolean>(false);
  const [showImproveWizard, setShowImproveWizard] = useState(false);

  const { toast } = useToast();

  const [datasetId] = useLocalStorageState<string | null>(
    PLAYGROUND_SELECTED_DATASET_VERSION_KEY,
    {
      defaultValue: null,
    },
  );

  const { promptId, content } = message;
  const { data: promptData } = usePromptById(
    { promptId: promptId! },
    { enabled: !!promptId },
  );

  // Check if content has meaningful text
  const hasContent = useMemo(() => {
    return Boolean(getTextFromMessageContent(content).trim());
  }, [content]);
  const showGenerateButton = improvePromptConfig && !hasContent;
  const showImproveButton = improvePromptConfig && hasContent;
  const hasModel = Boolean(improvePromptConfig?.model?.trim());
  const isPromptButtonDisabled = !hasModel;
  const promptButtonTooltip = !hasModel
    ? "Configure model first"
    : hasContent
      ? "Improve prompt"
      : "Generate prompt";

  const handleOpenWizard = useCallback(() => {
    setShowImproveWizard(true);
  }, []);

  // Auto-open improvement wizard when message autoImprove flag is set
  useEffect(() => {
    if (
      message.autoImprove &&
      improvePromptConfig &&
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
    improvePromptConfig,
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

  const handleDetachPrompt = useCallback(() => {
    onChangeMessage({
      promptId: undefined,
      promptVersionId: undefined,
    });
  }, [onChangeMessage]);

  const onSaveHandler = useCallback(
    (version: PromptVersion) => {
      onChangeMessage({
        promptId: version.prompt_id,
        content: parsePromptVersionContent(version),
        promptVersionId: version.id,
      });
    },
    [onChangeMessage],
  );

  const saveDisabled = message.content === "";
  const saveWarning = Boolean(
    !saveDisabled &&
      promptId &&
      promptData?.id === promptId &&
      !isEqual(
        message.content,
        parsePromptVersionContent(promptData?.latest_version),
      ),
  );
  const saveTooltip = saveWarning
    ? !datasetId
      ? "This prompt version hasn't been saved"
      : "This prompt version hasn't been saved. Save it to link it to the experiment and make comparisons easier."
    : "Save to prompt library";

  const onPromptSelectBoxOpenChange = useCallback(
    (open: boolean) => {
      isPromptSelectBoxOpenedRef.current = open;
      setIsHoldActionsVisible(isPromptSelectBoxOpenedRef.current);
    },
    [setIsHoldActionsVisible],
  );

  const confirmConfig = useMemo(() => {
    return {
      onConfirm: () => {
        handleUpdateExternalPromptId(tempPromptIdRef.current);
      },
      title: "Load prompt",
      description:
        "You have unsaved changes in your message field. Loading a new prompt will overwrite them with the prompt's content. This action cannot be undone.",
      confirmText: "Load prompt",
    };
  }, [handleUpdateExternalPromptId]);

  // This effect is used to set the template and promptVersionId after it is loaded,
  // after it was set in handleUpdateExternalPromptId function
  useEffect(() => {
    if (
      selectedPromptIdRef.current &&
      selectedPromptIdRef.current === promptId &&
      selectedPromptIdRef.current === promptData?.id
    ) {
      selectedPromptIdRef.current = undefined;

      const template = promptData.latest_version?.template ?? "";
      const versionId = promptData.latest_version?.id;
      const isChatPrompt =
        promptData.template_structure === PROMPT_TEMPLATE_STRUCTURE.CHAT;

      // If it's a chat prompt and we have the callback, replace all messages
      if (isChatPrompt && onReplaceWithChatPrompt && template) {
        const newMessages = parseChatTemplateToLLMMessages(template, {
          promptId: promptData.id,
          promptVersionId: versionId,
          useTimestamp: true,
        });

        if (newMessages.length > 0) {
          onReplaceWithChatPrompt(newMessages, promptData.id, versionId || "");
          setIsLoading(false);
          return;
        }
      }

      // For string prompts or if chat prompt parsing failed, update just this message
      // and clear prompt links from other messages
      if (onClearOtherPromptLinks) {
        onClearOtherPromptLinks();
      }
      onChangeMessage({
        content: parsePromptVersionContent(promptData.latest_version),
        promptVersionId: promptData.latest_version?.id,
        promptId: promptData.id,
      });
      setIsLoading(false);
    }
  }, [
    onChangeMessage,
    promptData,
    promptId,
    setIsLoading,
    onReplaceWithChatPrompt,
    onClearOtherPromptLinks,
  ]);

  return (
    <>
      <div className="flex cursor-default flex-nowrap items-center">
        {showGenerateButton && (
          <TooltipWrapper content={promptButtonTooltip}>
            <Button
              variant="minimal"
              size="icon-sm"
              onClick={handleOpenWizard}
              type="button"
              disabled={isPromptButtonDisabled}
            >
              <Sparkles />
            </Button>
          </TooltipWrapper>
        )}
        {showImproveButton && (
          <TooltipWrapper content={promptButtonTooltip}>
            <Button
              variant="minimal"
              size="icon-sm"
              onClick={handleOpenWizard}
              type="button"
              disabled={isPromptButtonDisabled}
            >
              <Wand2 />
            </Button>
          </TooltipWrapper>
        )}

        <PromptsSelectBox
          compact
          projectId={activeProjectId!}
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
          onClear={handleDetachPrompt}
          filterByTemplateStructure={PROMPT_TEMPLATE_STRUCTURE.TEXT}
          hasUnsavedChanges={saveWarning}
          promptName={promptData?.name}
        />

        {!saveDisabled && (
          <TooltipWrapper content={saveTooltip}>
            <Button
              variant="minimal"
              size="icon-sm"
              onClick={() => {
                resetKeyRef.current = resetKeyRef.current + 1;
                setOpen("save");
              }}
            >
              <Save />
            </Button>
          </TooltipWrapper>
        )}

        <ConfirmDialog
          key={`confirm-${resetKeyRef.current}`}
          open={open === "load"}
          setOpen={setOpen}
          {...confirmConfig}
        />
        <AddNewPromptVersionDialog
          key={`save-${resetKeyRef.current}`}
          open={open === "save"}
          setOpen={setOpen}
          prompt={promptData}
          template={convertMessageToMessagesJson(message)}
          metadata={{
            created_from: "opik_ui",
            type: "messages_json",
          }}
          onSave={onSaveHandler}
        />
      </div>
      {improvePromptConfig && (
        <PromptImprovementDialog
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

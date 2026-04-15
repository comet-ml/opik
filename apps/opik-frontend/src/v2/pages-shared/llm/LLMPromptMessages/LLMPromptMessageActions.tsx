import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { Save, Sparkles, Wand2 } from "lucide-react";
import isEqual from "fast-deep-equal";

import { OnChangeFn } from "@/types/shared";
import { LLMMessage, MessageContent } from "@/types/llm";
import { BlueprintPromptRef } from "@/types/playground";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { useActiveProjectId } from "@/store/AppStore";
import BlueprintPromptsSelectBox from "@/v2/pages-shared/llm/BlueprintPromptsSelectBox/BlueprintPromptsSelectBox";
import SaveExistingPromptDialog from "@/v2/pages-shared/llm/BlueprintPromptsSelectBox/SaveExistingPromptDialog";
import SaveAsNewBlueprintFieldDialog from "@/v2/pages-shared/llm/BlueprintPromptsSelectBox/SaveAsNewBlueprintFieldDialog";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import PromptImprovementDialog from "@/v2/pages-shared/llm/PromptImprovementDialog/PromptImprovementDialog";
import {
  LLMPromptConfigsType,
  COMPOSED_PROVIDER_TYPE,
} from "@/types/providers";
import { useToast } from "@/ui/use-toast";
import {
  getTextFromMessageContent,
  parsePromptVersionContent,
} from "@/lib/llm";
import usePromptByCommit from "@/api/prompts/usePromptByCommit";
import useSavePromptToBlueprint from "@/v2/pages-shared/llm/BlueprintPromptsSelectBox/useSavePromptToBlueprint";
import { usePermissions } from "@/contexts/PermissionsContext";

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
  const activeProjectId = useActiveProjectId();
  const { toast } = useToast();
  const {
    permissions: { canCreatePrompts },
  } = usePermissions();

  const resetKeyRef = useRef(0);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [showSaveExisting, setShowSaveExisting] = useState(false);
  const [showSaveNew, setShowSaveNew] = useState(false);
  const [showImproveWizard, setShowImproveWizard] = useState(false);
  const pendingRefRef = useRef<BlueprintPromptRef | undefined>();
  const loadedCommitRef = useRef<string | null>(null);

  const { content, blueprintRef } = message;

  // Resolve the loaded blueprint commit
  const { data: commitData } = usePromptByCommit(
    { commitId: blueprintRef?.commitId ?? "" },
    { enabled: !!blueprintRef?.commitId },
  );

  const { existingFieldNames, saveExistingVersion, saveAsNewField, isSaving } =
    useSavePromptToBlueprint(activeProjectId!);

  // Content checks
  const hasContent = useMemo(
    () => Boolean(getTextFromMessageContent(content).trim()),
    [content],
  );

  const loadedVersion = commitData?.requested_version;
  const loadedVersionForParse = useMemo(
    () =>
      loadedVersion
        ? {
            template: loadedVersion.template,
            metadata: loadedVersion.metadata ?? undefined,
          }
        : undefined,
    [loadedVersion],
  );

  const hasUnsavedChanges = useMemo(() => {
    if (!blueprintRef || !loadedVersionForParse) return false;
    return !isEqual(content, parsePromptVersionContent(loadedVersionForParse));
  }, [blueprintRef, loadedVersionForParse, content]);

  // Populate message content when a blueprint prompt is loaded
  useEffect(() => {
    if (!blueprintRef || !commitData) return;
    if (loadedCommitRef.current === blueprintRef.commitId) return;
    loadedCommitRef.current = blueprintRef.commitId;

    if (commitData.template_structure === PROMPT_TEMPLATE_STRUCTURE.CHAT) {
      setIsLoading(false);
      return;
    }

    const loadedContent = parsePromptVersionContent(loadedVersionForParse);
    onChangeMessage({ content: loadedContent });
    setIsLoading(false);
  }, [
    blueprintRef,
    commitData,
    loadedVersionForParse,
    onChangeMessage,
    setIsLoading,
  ]);

  // Auto-open improvement wizard
  useEffect(() => {
    if (
      message.autoImprove &&
      improvePromptConfig &&
      blueprintRef &&
      hasContent
    ) {
      const timeoutId = setTimeout(() => {
        onChangeMessage({ autoImprove: false });
        if (!improvePromptConfig.model?.trim()) {
          toast({
            title: "Model configuration required",
            description:
              "Please configure a model and provider before improving the prompt.",
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
    blueprintRef,
    hasContent,
    onChangeMessage,
    toast,
  ]);

  const handleSelectRef = useCallback(
    (ref: BlueprintPromptRef) => {
      if (hasContent) {
        pendingRefRef.current = ref;
        resetKeyRef.current++;
        setConfirmOpen(true);
      } else {
        setIsLoading(true);
        onChangeMessage({ blueprintRef: ref });
      }
    },
    [hasContent, onChangeMessage, setIsLoading],
  );

  const handleConfirmLoad = useCallback(() => {
    setIsLoading(true);
    onChangeMessage({ blueprintRef: pendingRefRef.current });
    pendingRefRef.current = undefined;
  }, [onChangeMessage, setIsLoading]);

  const handleDetach = useCallback(() => {
    onChangeMessage({ blueprintRef: undefined });
    loadedCommitRef.current = null;
  }, [onChangeMessage]);

  const onPromptSelectBoxOpenChange = useCallback(
    (open: boolean) => setIsHoldActionsVisible(open),
    [setIsHoldActionsVisible],
  );

  // Improve prompt buttons
  const hasModel = Boolean(improvePromptConfig?.model?.trim());
  const isPromptButtonDisabled = !hasModel;
  const promptButtonTooltip = !hasModel
    ? "Configure model first"
    : hasContent
      ? "Improve prompt"
      : "Generate prompt";

  const handleOpenWizard = useCallback(() => setShowImproveWizard(true), []);

  // Save handlers
  const handleClickSave = useCallback(() => {
    if (blueprintRef) {
      setShowSaveExisting(true);
    } else {
      setShowSaveNew(true);
    }
  }, [blueprintRef]);

  const handleSaveExisting = useCallback(
    async (changeDescription: string) => {
      if (!blueprintRef || !commitData) return;
      const result = await saveExistingVersion({
        ref: blueprintRef,
        promptName: commitData.name,
        template: getTextFromMessageContent(content),
        templateStructure: PROMPT_TEMPLATE_STRUCTURE.TEXT,
        changeDescription: changeDescription || undefined,
      });
      if (!result) return;
      onChangeMessage({ blueprintRef: result.newRef });
      loadedCommitRef.current = result.newRef.commitId;
      setShowSaveExisting(false);
    },
    [blueprintRef, commitData, content, saveExistingVersion, onChangeMessage],
  );

  const handleSaveNewField = useCallback(
    async (fieldName: string, changeDescription: string) => {
      const newRef = await saveAsNewField({
        fieldName,
        template: getTextFromMessageContent(content),
        templateStructure: PROMPT_TEMPLATE_STRUCTURE.TEXT,
        changeDescription: changeDescription || undefined,
      });
      if (!newRef) return;
      onChangeMessage({ blueprintRef: newRef });
      loadedCommitRef.current = newRef.commitId;
      setShowSaveNew(false);
    },
    [content, saveAsNewField, onChangeMessage],
  );

  const saveTooltip = blueprintRef
    ? "Update prompt in agent configuration"
    : "Save as new field in agent configuration";

  return (
    <>
      <div className="flex min-w-0 cursor-default flex-nowrap items-center">
        {improvePromptConfig && (
          <div className="shrink-0">
            <TooltipWrapper content={promptButtonTooltip}>
              <Button
                variant="minimal"
                size="icon-sm"
                onClick={handleOpenWizard}
                type="button"
                disabled={isPromptButtonDisabled}
              >
                {hasContent ? <Wand2 /> : <Sparkles />}
              </Button>
            </TooltipWrapper>
          </div>
        )}

        <BlueprintPromptsSelectBox
          projectId={activeProjectId!}
          value={blueprintRef}
          onValueChange={handleSelectRef}
          onClear={handleDetach}
          onOpenChange={onPromptSelectBoxOpenChange}
          hasUnsavedChanges={hasUnsavedChanges}
          filterByTemplateStructure={PROMPT_TEMPLATE_STRUCTURE.TEXT}
        />

        {hasContent && (
          <div className="shrink-0">
            <TooltipWrapper content={saveTooltip}>
              <Button
                variant="minimal"
                size="icon-sm"
                onClick={handleClickSave}
                disabled={!canCreatePrompts || isSaving}
              >
                <Save />
              </Button>
            </TooltipWrapper>
          </div>
        )}

        <ConfirmDialog
          key={`confirm-${resetKeyRef.current}`}
          open={confirmOpen}
          setOpen={setConfirmOpen}
          onConfirm={handleConfirmLoad}
          title="Load prompt"
          description="You have unsaved changes in your message field. Loading a new prompt will overwrite them. This action cannot be undone."
          confirmText="Load prompt"
        />

        {blueprintRef && commitData && (
          <SaveExistingPromptDialog
            open={showSaveExisting}
            onOpenChange={setShowSaveExisting}
            promptName={commitData.name}
            fieldName={blueprintRef.key}
            isSaving={isSaving}
            onSave={handleSaveExisting}
          />
        )}

        {showSaveNew && (
          <SaveAsNewBlueprintFieldDialog
            open={showSaveNew}
            onOpenChange={setShowSaveNew}
            existingFieldNames={existingFieldNames}
            isSaving={isSaving}
            onSave={handleSaveNewField}
          />
        )}
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

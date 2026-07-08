import React, { useCallback, useMemo, useState } from "react";
import { UseFormReturn } from "react-hook-form";
import { FileTerminal, Save } from "lucide-react";
import { usePermissions } from "@/contexts/PermissionsContext";
import useAppStore from "@/store/AppStore";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import {
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/ui/form";
import { OptimizationConfigFormType } from "@/v2/pages-shared/optimizations/OptimizationConfigForm/schema";
import {
  COMPOSED_PROVIDER_TYPE,
  PROVIDER_MODEL_TYPE,
  LLMPromptConfigsType,
} from "@/types/providers";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import LLMPromptMessages from "@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import PromptModelSelect from "@/v2/pages-shared/llm/PromptModelSelect/PromptModelSelect";
import PromptModelConfigs from "@/v2/pages-shared/llm/PromptModelSettings/PromptModelConfigs";
import PromptLibraryMenu from "@/v2/pages-shared/llm/PromptLibraryMenu/PromptLibraryMenu";
import LoadedPromptDisplay from "@/v2/pages-shared/llm/LoadedPromptDisplay/LoadedPromptDisplay";
import { OPTIMIZATION_MESSAGE_TYPE_OPTIONS } from "@/constants/optimizations";
import {
  PROMPT_SAVE_AS_CHAT_TOOLTIP,
  PROMPT_UNSAVED_TOOLTIP,
} from "@/constants/prompts";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import AddNewPromptVersionDialog from "@/v2/pages-shared/llm/LLMPromptMessages/AddNewPromptVersionDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import useLoadChatPrompt from "@/hooks/useLoadChatPrompt";
import usePromptVersionLabel from "@/hooks/usePromptVersionLabel";

type OptimizationsNewPromptSectionProps = {
  form: UseFormReturn<OptimizationConfigFormType>;
  projectId: string;
  model: PROVIDER_MODEL_TYPE | "";
  config: OptimizationConfigFormType["modelConfig"];
  datasetVariables: string[];
  onNameChange: (value: string) => void;
  onModelChange: (model: PROVIDER_MODEL_TYPE) => void;
  onModelConfigChange: (configs: Partial<LLMPromptConfigsType>) => void;
};

const OptimizationsNewPromptSection: React.FC<
  OptimizationsNewPromptSectionProps
> = ({
  form,
  projectId,
  model,
  config,
  datasetVariables,
  onNameChange,
  onModelChange,
  onModelConfigChange,
}) => {
  const {
    permissions: { canCreatePrompts },
  } = usePermissions();

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { calculateModelProvider } = useLLMProviderModelsData();

  // The form has no `provider` field, so derive it from the selected model for
  // the model picker + params popover.
  const provider = useMemo<COMPOSED_PROVIDER_TYPE | "">(
    () => (model ? calculateModelProvider(model) : ""),
    [calculateModelProvider, model],
  );

  const [selectedChatPromptId, setSelectedChatPromptId] = useState<
    string | undefined
  >(undefined);
  const [selectedChatPromptVersionId, setSelectedChatPromptVersionId] =
    useState<string | undefined>(undefined);
  const [showSaveChatPromptDialog, setShowSaveChatPromptDialog] =
    useState(false);
  const [lastImportedPromptName, setLastImportedPromptName] =
    useState<string>("");

  const messages = form.watch("messages");

  const handleMessagesLoaded = useCallback(
    (newMessages: LLMMessage[], promptName: string) => {
      setLastImportedPromptName(promptName);
      form.setValue("messages", newMessages, {
        shouldDirty: true,
      });

      if (promptName) {
        onNameChange(promptName);
      }
    },
    [form, onNameChange],
  );

  const handleImportChatPrompt = useCallback(
    (loadedPromptId?: string, loadedVersionId?: string) => {
      setSelectedChatPromptId(loadedPromptId);
      setSelectedChatPromptVersionId(loadedVersionId);
    },
    [],
  );

  const {
    chatPromptData,
    chatPromptVersionData,
    chatPromptTemplate,
    hasUnsavedChatPromptChanges,
  } = useLoadChatPrompt({
    selectedChatPromptId,
    selectedChatPromptVersionId,
    messages,
    onMessagesLoaded: handleMessagesLoaded,
    onPromptUnavailable: () => handleImportChatPrompt(undefined, undefined),
  });

  const chatPromptVersionLabel = usePromptVersionLabel(
    selectedChatPromptId,
    selectedChatPromptVersionId ?? chatPromptVersionData?.id,
    chatPromptData?.version_count,
  );

  const handleSaveChatPrompt = useCallback(() => {
    setShowSaveChatPromptDialog(true);
  }, []);

  const hasMessages = messages.some((msg) =>
    typeof msg.content === "string"
      ? msg.content.trim()
      : Array.isArray(msg.content) && msg.content.length > 0,
  );

  return (
    <div className="min-w-0 flex-1 space-y-2">
      <FormField
        control={form.control}
        name="name"
        render={({ field }) => (
          <FormItem className="gap-1.5">
            <FormLabel className="comet-body-s-accented">Name</FormLabel>
            <FormControl>
              <Input
                {...field}
                onChange={(e) => onNameChange(e.target.value)}
                placeholder="Name (auto-generated if left blank)"
                dimension="sm"
              />
            </FormControl>
            <FormMessage />
          </FormItem>
        )}
      />

      <div>
        <div className="flex h-8 items-center justify-between gap-2">
          <div className="flex min-w-0 items-center gap-1">
            <Label className="comet-body-s-accented shrink-0">Prompt</Label>
            <Separator orientation="vertical" className="mx-1 h-4" />
            <FormField
              control={form.control}
              name="modelName"
              render={({ field }) => (
                <FormItem className="flex min-w-0 flex-row items-center gap-1 space-y-0">
                  <FormControl>
                    <PromptModelSelect
                      compact
                      value={field.value as PROVIDER_MODEL_TYPE | ""}
                      provider={provider}
                      workspaceName={workspaceName}
                      onChange={(newModel) => onModelChange(newModel)}
                      hasError={Boolean(form.formState.errors.modelName)}
                    />
                  </FormControl>
                  <PromptModelConfigs
                    provider={provider as COMPOSED_PROVIDER_TYPE}
                    model={model}
                    configs={config}
                    onChange={onModelConfigChange}
                    size="icon-2xs"
                    variant="ghost"
                  />
                </FormItem>
              )}
            />
          </div>

          <div className="flex shrink-0 items-center">
            {selectedChatPromptId ? (
              <LoadedPromptDisplay
                name={chatPromptData?.name}
                templateStructure={PROMPT_TEMPLATE_STRUCTURE.CHAT}
                versionLabel={chatPromptVersionLabel}
                versionTags={
                  chatPromptVersionData?.tags ??
                  chatPromptData?.latest_version?.tags
                }
                versionEnvironments={
                  chatPromptVersionData?.environments ??
                  chatPromptData?.latest_version?.environments
                }
                hasUnsavedChanges={hasUnsavedChatPromptChanges}
                onClear={() => handleImportChatPrompt(undefined, undefined)}
              />
            ) : (
              <PromptLibraryMenu
                projectId={projectId}
                filterByTemplateStructure={PROMPT_TEMPLATE_STRUCTURE.CHAT}
                onSelect={({ promptId: pId, versionId }) =>
                  handleImportChatPrompt(pId, versionId)
                }
                trigger={
                  <div>
                    <TooltipWrapper content="Load prompt">
                      <Button
                        variant="minimal"
                        size="icon-2xs"
                        type="button"
                        data-testid="load-prompt-button"
                      >
                        <FileTerminal />
                      </Button>
                    </TooltipWrapper>
                  </div>
                }
              />
            )}
            {hasMessages && (
              <TooltipWrapper
                content={
                  hasUnsavedChatPromptChanges
                    ? PROMPT_UNSAVED_TOOLTIP
                    : PROMPT_SAVE_AS_CHAT_TOOLTIP
                }
              >
                <Button
                  variant="minimal"
                  size="icon-2xs"
                  onClick={handleSaveChatPrompt}
                  disabled={!canCreatePrompts && !selectedChatPromptId}
                  badge={hasUnsavedChatPromptChanges}
                  type="button"
                >
                  <Save />
                </Button>
              </TooltipWrapper>
            )}
          </div>
        </div>
        <FormField
          control={form.control}
          name="messages"
          render={({ field }) => {
            const fieldMessages = field.value;

            return (
              <FormItem className="gap-0">
                <LLMPromptMessages
                  messages={fieldMessages}
                  possibleTypes={OPTIMIZATION_MESSAGE_TYPE_OPTIONS}
                  hidePromptActions={false}
                  disableMedia
                  promptVariables={datasetVariables}
                  onChange={(newMessages: LLMMessage[]) => {
                    field.onChange(newMessages);
                  }}
                  onAddMessage={() =>
                    field.onChange([
                      ...fieldMessages,
                      generateDefaultLLMPromptMessage({
                        role: LLM_MESSAGE_ROLE.user,
                      }),
                    ])
                  }
                />
                <FormMessage className="mt-1.5" />
              </FormItem>
            );
          }}
        />
      </div>

      <AddNewPromptVersionDialog
        open={showSaveChatPromptDialog}
        setOpen={setShowSaveChatPromptDialog}
        prompt={chatPromptData}
        template={chatPromptTemplate}
        templateStructure={PROMPT_TEMPLATE_STRUCTURE.CHAT}
        defaultName={lastImportedPromptName}
        onSave={(version, _promptName, savedPromptId) => {
          setShowSaveChatPromptDialog(false);

          if (savedPromptId) {
            handleImportChatPrompt(savedPromptId, version?.id);
          }
        }}
      />
    </div>
  );
};

export default OptimizationsNewPromptSection;

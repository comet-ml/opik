import React, { useCallback, useState } from "react";
import { UseFormReturn } from "react-hook-form";
import { Save } from "lucide-react";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import {
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { OptimizationConfigFormType } from "@/components/pages-shared/optimizations/OptimizationConfigForm/schema";
import { PROVIDER_MODEL_TYPE, LLMPromptConfigsType } from "@/types/providers";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import LLMPromptMessages from "@/components/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import OptimizationModelSelect from "@/components/pages-shared/optimizations/OptimizationModelSelect/OptimizationModelSelect";
import OptimizationTemperatureConfig from "@/components/pages-shared/optimizations/OptimizationConfigForm/OptimizationTemperatureConfig";
import { OPTIMIZATION_MESSAGE_TYPE_OPTIONS } from "@/constants/optimizations";
import PromptsSelectBox from "@/components/pages-shared/llm/PromptsSelectBox/PromptsSelectBox";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import useLoadChatPrompt from "@/hooks/useLoadChatPrompt";
import AddNewPromptVersionDialog from "@/components/pages-shared/llm/LLMPromptMessages/AddNewPromptVersionDialog";

type OptimizationsNewPromptSectionProps = {
  form: UseFormReturn<OptimizationConfigFormType>;
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
  model,
  config,
  datasetVariables,
  onNameChange,
  onModelChange,
  onModelConfigChange,
}) => {
  const [selectedChatPromptId, setSelectedChatPromptId] = useState<
    string | undefined
  >(undefined);
  const [showSaveChatPromptDialog, setShowSaveChatPromptDialog] =
    useState(false);
  const [lastImportedPromptName, setLastImportedPromptName] =
    useState<string>("");

  const messages = form.watch("messages");

  const handleMessagesLoaded = useCallback(
    (newMessages: LLMMessage[], promptName: string) => {
      setLastImportedPromptName(promptName);
      form.setValue("messages", newMessages, { shouldValidate: true });

      // hack: needed to be able to save the prompt with the same name
      if (promptName) {
        onNameChange(promptName);
      }
    },
    [form, onNameChange],
  );

  const { chatPromptData, chatPromptTemplate, hasUnsavedChatPromptChanges } =
    useLoadChatPrompt({
      selectedChatPromptId,
      messages,
      onMessagesLoaded: handleMessagesLoaded,
    });

  const handleImportChatPrompt = useCallback((loadedPromptId: string) => {
    setSelectedChatPromptId(loadedPromptId);
  }, []);

  const handleSaveChatPrompt = useCallback(() => {
    setShowSaveChatPromptDialog(true);
  }, []);

  return (
    <div className="flex-1 space-y-6">
      <FormField
        control={form.control}
        name="name"
        render={({ field }) => (
          <FormItem>
            <FormLabel className="comet-body-s-accented">Name</FormLabel>
            <FormControl>
              <Input
                {...field}
                onChange={(e) => onNameChange(e.target.value)}
                placeholder="Enter optimization name, or the name will be generated automatically"
                className="h-10"
              />
            </FormControl>
            <FormMessage />
          </FormItem>
        )}
      />

      <div>
        <div className="mb-2 flex h-8 items-center justify-between">
          <Label className="comet-body-s-accented">Prompt</Label>
          <div className="flex h-full items-center gap-1">
            <TooltipWrapper
              content={chatPromptData?.name || "Load chat prompt"}
            >
              <div className="flex h-full min-w-40 max-w-60 flex-auto flex-nowrap">
                <PromptsSelectBox
                  value={selectedChatPromptId}
                  onValueChange={(value) =>
                    value && handleImportChatPrompt(value)
                  }
                  clearable={false}
                  filterByTemplateStructure={PROMPT_TEMPLATE_STRUCTURE.CHAT}
                />
              </div>
            </TooltipWrapper>
            <TooltipWrapper
              content={
                hasUnsavedChatPromptChanges
                  ? "This prompt version hasn't been saved"
                  : "Save as chat prompt"
              }
            >
              <Button
                variant="outline"
                size="icon-sm"
                onClick={handleSaveChatPrompt}
                badge={hasUnsavedChatPromptChanges}
                type="button"
              >
                <Save />
              </Button>
            </TooltipWrapper>
            <Separator orientation="vertical" className="h-6" />
            <FormField
              control={form.control}
              name="modelName"
              render={({ field }) => (
                <FormItem className="flex h-full items-center gap-1">
                  <FormControl>
                    <div className="h-full w-56">
                      <OptimizationModelSelect
                        value={field.value as PROVIDER_MODEL_TYPE | ""}
                        onChange={onModelChange}
                        hasError={Boolean(form.formState.errors.modelName)}
                      />
                    </div>
                  </FormControl>
                  <OptimizationTemperatureConfig
                    size="icon-sm"
                    model={model}
                    configs={config}
                    onChange={onModelConfigChange}
                  />
                </FormItem>
              )}
            />
          </div>
        </div>
        <FormField
          control={form.control}
          name="messages"
          render={({ field }) => {
            const fieldMessages = field.value;

            return (
              <FormItem>
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
                <FormMessage />
              </FormItem>
            );
          }}
        />
      </div>

      {/* Save Chat Prompt Dialog */}
      <AddNewPromptVersionDialog
        open={showSaveChatPromptDialog}
        setOpen={setShowSaveChatPromptDialog}
        prompt={chatPromptData}
        template={chatPromptTemplate}
        templateStructure={PROMPT_TEMPLATE_STRUCTURE.CHAT}
        defaultName={lastImportedPromptName}
        onSave={(version, _promptName, savedPromptId) => {
          setShowSaveChatPromptDialog(false);

          // Update the loaded chat prompt ID to the saved prompt
          if (savedPromptId) {
            setSelectedChatPromptId(savedPromptId);
          }
        }}
      />
    </div>
  );
};

export default OptimizationsNewPromptSection;

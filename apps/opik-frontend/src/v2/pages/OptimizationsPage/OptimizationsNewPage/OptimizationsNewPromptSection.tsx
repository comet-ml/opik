import React, { useCallback, useState } from "react";
import { UseFormReturn } from "react-hook-form";
import { Save } from "lucide-react";
import { usePermissions } from "@/contexts/PermissionsContext";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { Button } from "@/ui/button";
import {
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/ui/form";
import { OptimizationConfigFormType } from "@/v2/pages-shared/optimizations/OptimizationConfigForm/schema";
import { PROVIDER_MODEL_TYPE, LLMPromptConfigsType } from "@/types/providers";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import LLMPromptMessages from "@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import OptimizationModelSelect from "@/v2/pages-shared/optimizations/OptimizationModelSelect/OptimizationModelSelect";
import OptimizationTemperatureConfig from "@/v2/pages-shared/optimizations/OptimizationConfigForm/OptimizationTemperatureConfig";
import { OPTIMIZATION_MESSAGE_TYPE_OPTIONS } from "@/constants/optimizations";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import BlueprintPromptsSelectBox from "@/v2/pages-shared/llm/BlueprintPromptsSelectBox/BlueprintPromptsSelectBox";
import SaveExistingPromptDialog from "@/v2/pages-shared/llm/BlueprintPromptsSelectBox/SaveExistingPromptDialog";
import SaveAsNewBlueprintFieldDialog from "@/v2/pages-shared/llm/BlueprintPromptsSelectBox/SaveAsNewBlueprintFieldDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { BlueprintPromptRef } from "@/types/playground";

type OptimizationsNewPromptSectionProps = {
  form: UseFormReturn<OptimizationConfigFormType>;
  projectId: string;
  model: PROVIDER_MODEL_TYPE | "";
  config: OptimizationConfigFormType["modelConfig"];
  datasetVariables: string[];
  onNameChange: (value: string) => void;
  onModelChange: (model: PROVIDER_MODEL_TYPE) => void;
  onModelConfigChange: (configs: Partial<LLMPromptConfigsType>) => void;
  blueprintRef?: BlueprintPromptRef;
  blueprintPromptName?: string;
  blueprintFieldNames: string[];
  isSavingBlueprint: boolean;
  hasUnsavedBlueprintChanges: boolean;
  onBlueprintRefChange: (ref: BlueprintPromptRef) => void;
  onBlueprintRefClear: () => void;
  onSaveBlueprintExisting: (changeDescription: string) => Promise<unknown>;
  onSaveBlueprintNewField: (
    fieldName: string,
    changeDescription: string,
  ) => Promise<unknown>;
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
  blueprintRef,
  blueprintPromptName,
  blueprintFieldNames,
  isSavingBlueprint,
  hasUnsavedBlueprintChanges,
  onBlueprintRefChange,
  onBlueprintRefClear,
  onSaveBlueprintExisting,
  onSaveBlueprintNewField,
}) => {
  const {
    permissions: { canCreatePrompts },
  } = usePermissions();

  const [showSaveExisting, setShowSaveExisting] = useState(false);
  const [showSaveNew, setShowSaveNew] = useState(false);

  const hasMessages = form
    .watch("messages")
    .some((msg) =>
      typeof msg.content === "string"
        ? msg.content.trim()
        : Array.isArray(msg.content) && msg.content.length > 0,
    );

  const handleClickSave = useCallback(() => {
    if (blueprintRef) {
      setShowSaveExisting(true);
    } else {
      setShowSaveNew(true);
    }
  }, [blueprintRef]);

  const handleSaveExisting = useCallback(
    async (changeDescription: string) => {
      const result = await onSaveBlueprintExisting(changeDescription);
      if (!result) return;
      setShowSaveExisting(false);
    },
    [onSaveBlueprintExisting],
  );

  const handleSaveNew = useCallback(
    async (fieldName: string, changeDescription: string) => {
      const result = await onSaveBlueprintNewField(
        fieldName,
        changeDescription,
      );
      if (!result) return;
      setShowSaveNew(false);
    },
    [onSaveBlueprintNewField],
  );

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
          <div className="flex items-center gap-0.5">
            <Label className="comet-body-s-accented">Prompt</Label>
            <BlueprintPromptsSelectBox
              projectId={projectId}
              value={blueprintRef}
              onValueChange={onBlueprintRefChange}
              onClear={onBlueprintRefClear}
              hasUnsavedChanges={hasUnsavedBlueprintChanges}
              filterByTemplateStructure={PROMPT_TEMPLATE_STRUCTURE.CHAT}
            />
            {hasMessages && (
              <TooltipWrapper
                content={
                  blueprintRef
                    ? "Update prompt in agent configuration"
                    : "Save as new field in agent configuration"
                }
              >
                <Button
                  variant="minimal"
                  size="icon-sm"
                  onClick={handleClickSave}
                  disabled={!canCreatePrompts || isSavingBlueprint}
                >
                  <Save />
                </Button>
              </TooltipWrapper>
            )}
          </div>
          <div className="flex h-full items-center gap-1">
            <FormField
              control={form.control}
              name="modelName"
              render={({ field }) => (
                <FormItem className="flex h-full flex-row items-center gap-1">
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

      {blueprintRef && (
        <SaveExistingPromptDialog
          open={showSaveExisting}
          onOpenChange={setShowSaveExisting}
          promptName={blueprintPromptName ?? blueprintRef.key}
          fieldName={blueprintRef.key}
          isSaving={isSavingBlueprint}
          onSave={handleSaveExisting}
        />
      )}

      {showSaveNew && (
        <SaveAsNewBlueprintFieldDialog
          open={showSaveNew}
          onOpenChange={setShowSaveNew}
          existingFieldNames={blueprintFieldNames}
          isSaving={isSavingBlueprint}
          onSave={handleSaveNew}
        />
      )}
    </div>
  );
};

export default OptimizationsNewPromptSection;

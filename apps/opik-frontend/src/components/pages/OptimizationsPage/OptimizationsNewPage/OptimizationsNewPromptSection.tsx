import React from "react";
import { UseFormReturn } from "react-hook-form";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
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
        <div className="mb-2 flex items-center justify-between">
          <Label className="comet-body-s-accented">Prompt</Label>
          <FormField
            control={form.control}
            name="modelName"
            render={({ field }) => (
              <FormItem className="flex items-center gap-2">
                <FormControl>
                  <div className="flex h-7 items-center gap-1">
                    <div className="h-full w-56">
                      <OptimizationModelSelect
                        value={field.value as PROVIDER_MODEL_TYPE | ""}
                        onChange={onModelChange}
                        hasError={Boolean(form.formState.errors.modelName)}
                      />
                    </div>
                    <OptimizationTemperatureConfig
                      size="icon-xs"
                      model={model}
                      configs={config}
                      onChange={onModelConfigChange}
                    />
                  </div>
                </FormControl>
              </FormItem>
            )}
          />
        </div>
        <FormField
          control={form.control}
          name="messages"
          render={({ field }) => {
            const messages = field.value;

            return (
              <FormItem>
                <LLMPromptMessages
                  messages={messages}
                  possibleTypes={OPTIMIZATION_MESSAGE_TYPE_OPTIONS}
                  hidePromptActions
                  disableMedia
                  promptVariables={datasetVariables}
                  onChange={(messages: LLMMessage[]) => {
                    field.onChange(messages);
                  }}
                  onAddMessage={() =>
                    field.onChange([
                      ...messages,
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
    </div>
  );
};

export default OptimizationsNewPromptSection;

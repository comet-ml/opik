import React, { useCallback, useRef } from "react";
import { UseFormReturn } from "react-hook-form";
import { Info } from "lucide-react";
import find from "lodash/find";
import get from "lodash/get";

import { Label } from "@/components/ui/label";
import {
  FormControl,
  FormField,
  FormItem,
  FormMessage,
} from "@/components/ui/form";
import PromptModelSelect from "@/components/pages-shared/llm/PromptModelSelect/PromptModelSelect";
import PromptModelConfigs from "@/components/pages-shared/llm/PromptModelSettings/PromptModelConfigs";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import LLMPromptMessages from "@/components/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import LLMPromptMessagesVariables from "@/components/pages-shared/llm/LLMPromptMessagesVariables/LLMPromptMessagesVariables";
import LLMJudgeScores from "@/components/pages-shared/llm/LLMJudgeScores/LLMJudgeScores";
import {
  LLM_MESSAGE_ROLE_NAME_MAP,
  LLM_PROMPT_TEMPLATES,
} from "@/constants/llm";
import {
  LLM_JUDGE,
  LLM_MESSAGE_ROLE,
  LLMMessage,
  LLMPromptTemplate,
} from "@/types/llm";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { safelyGetPromptMustacheTags } from "@/lib/prompt";
import { EvaluationRuleFormType } from "@/components/pages-shared/automations/AddEditRuleDialog/schema";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";

const MESSAGE_TYPE_OPTIONS = [
  {
    label: LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE.system],
    value: LLM_MESSAGE_ROLE.system,
  },
  {
    label: LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE.user],
    value: LLM_MESSAGE_ROLE.user,
  },
  {
    label: LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE.ai],
    value: LLM_MESSAGE_ROLE.ai,
  },
  {
    label: LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE.tool_execution_result],
    value: LLM_MESSAGE_ROLE.tool_execution_result,
  },
];

type LLMJudgeRuleDetailsProps = {
  workspaceName: string;
  form: UseFormReturn<EvaluationRuleFormType>;
};

const LLMJudgeRuleDetails: React.FC<LLMJudgeRuleDetailsProps> = ({
  workspaceName,
  form,
}) => {
  const cache = useRef<Record<string | LLM_JUDGE, LLMPromptTemplate>>({});
  const { calculateModelProvider, calculateDefaultModel } =
    useLLMProviderModelsData();

  const handleAddProvider = useCallback(
    (provider: PROVIDER_TYPE) => {
      const model =
        (form.watch("llmJudgeDetails.model") as PROVIDER_MODEL_TYPE) || "";

      if (!model) {
        form.setValue(
          "llmJudgeDetails.model",
          calculateDefaultModel(model, [provider], provider, {
            structuredOutput: true,
          }),
        );
      }
    },
    [calculateDefaultModel, form],
  );

  const handleDeleteProvider = useCallback(
    (provider: PROVIDER_TYPE) => {
      const model =
        (form.watch("llmJudgeDetails.model") as PROVIDER_MODEL_TYPE) || "";
      const currentProvider = calculateModelProvider(model);
      if (currentProvider === provider) {
        form.setValue("llmJudgeDetails.model", "");
      }
    },
    [calculateModelProvider, form],
  );

  return (
    <>
      <FormField
        control={form.control}
        name="llmJudgeDetails.model"
        render={({ field, formState }) => {
          const model = field.value as PROVIDER_MODEL_TYPE | "";
          const provider = calculateModelProvider(model);
          const validationErrors = get(formState.errors, [
            "llmJudgeDetails",
            "model",
          ]);

          return (
            <FormItem>
              <Label>Model</Label>
              <FormControl>
                <div className="flex h-10 items-center justify-center gap-2">
                  <PromptModelSelect
                    value={model}
                    onChange={(m) => {
                      if (m) {
                        field.onChange(m);
                      }
                    }}
                    provider={provider}
                    hasError={Boolean(validationErrors?.message)}
                    workspaceName={workspaceName}
                    onAddProvider={handleAddProvider}
                    onDeleteProvider={handleDeleteProvider}
                    onlyWithStructuredOutput
                  />

                  <FormField
                    control={form.control}
                    name="llmJudgeDetails.config"
                    render={({ field }) => (
                      <PromptModelConfigs
                        size="icon"
                        provider={provider}
                        configs={field.value}
                        onChange={field.onChange}
                      />
                    )}
                  ></FormField>
                </div>
              </FormControl>
              <FormMessage />
            </FormItem>
          );
        }}
      />
      <FormField
        control={form.control}
        name="llmJudgeDetails.template"
        render={({ field }) => (
          <FormItem>
            <Label>Prompt</Label>
            <FormControl>
              <SelectBox
                value={field.value}
                onChange={(newTemplate: string) => {
                  const { variables, messages, schema, template } =
                    form.getValues("llmJudgeDetails");
                  if (newTemplate !== template) {
                    cache.current[template] = {
                      ...cache.current[template],
                      messages: messages,
                      variables: variables,
                      schema: schema,
                    };

                    const templateData =
                      cache.current[newTemplate] ??
                      find(
                        LLM_PROMPT_TEMPLATES,
                        (t) => t.value === newTemplate,
                      );

                    form.setValue(
                      "llmJudgeDetails.messages",
                      templateData.messages,
                    );
                    form.setValue(
                      "llmJudgeDetails.variables",
                      templateData.variables,
                    );
                    form.setValue(
                      "llmJudgeDetails.schema",
                      templateData.schema,
                    );
                    form.setValue(
                      "llmJudgeDetails.template",
                      newTemplate as LLM_JUDGE,
                    );
                  }
                }}
                options={LLM_PROMPT_TEMPLATES}
              />
            </FormControl>
            <FormMessage />
          </FormItem>
        )}
      />
      <div className="-mt-2 flex flex-col gap-2">
        <FormField
          control={form.control}
          name="llmJudgeDetails.messages"
          render={({ field, formState }) => {
            const messages = field.value;
            const validationErrors = get(formState.errors, [
              "llmJudgeDetails",
              "messages",
            ]);

            return (
              <>
                <LLMPromptMessages
                  messages={messages}
                  validationErrors={validationErrors}
                  possibleTypes={MESSAGE_TYPE_OPTIONS}
                  onChange={(messages: LLMMessage[]) => {
                    field.onChange(messages);

                    // recalculate variables
                    const variables = form.getValues(
                      "llmJudgeDetails.variables",
                    );
                    const localVariables: Record<string, string> = {};
                    let parsingVariablesError: boolean = false;
                    messages
                      .reduce<string[]>((acc, m) => {
                        const tags = safelyGetPromptMustacheTags(m.content);
                        if (!tags) {
                          parsingVariablesError = true;
                          return acc;
                        } else {
                          return acc.concat(tags);
                        }
                      }, [])
                      .filter((v) => v !== "")
                      .forEach(
                        (v: string) => (localVariables[v] = variables[v] ?? ""),
                      );

                    form.setValue("llmJudgeDetails.variables", localVariables);
                    form.setValue(
                      "llmJudgeDetails.parsingVariablesError",
                      parsingVariablesError,
                    );
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
              </>
            );
          }}
        />
        <FormField
          control={form.control}
          name="llmJudgeDetails.variables"
          render={({ field, formState }) => {
            const parsingVariablesError = form.getValues(
              "llmJudgeDetails.parsingVariablesError",
            );
            const validationErrors = get(formState.errors, [
              "llmJudgeDetails",
              "variables",
            ]);

            return (
              <>
                <LLMPromptMessagesVariables
                  parsingError={parsingVariablesError}
                  validationErrors={validationErrors}
                  projectId={form.watch("projectId")}
                  variables={field.value}
                  onChange={field.onChange}
                />
              </>
            );
          }}
        />
      </div>
      <div className="flex flex-col gap-2">
        <div className="flex items-center">
          <Label htmlFor="name">Score definition</Label>
          <TooltipWrapper
            content={`The score definition is used to define which
feedback scores are returned by this rule.
To return more than one score, simply add
multiple scores to this section.`}
          >
            <Info className="ml-1 size-4 text-light-slate" />
          </TooltipWrapper>
        </div>
        <FormField
          control={form.control}
          name="llmJudgeDetails.schema"
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, [
              "llmJudgeDetails",
              "schema",
            ]);

            return (
              <LLMJudgeScores
                validationErrors={validationErrors}
                scores={field.value}
                onChange={field.onChange}
              />
            );
          }}
        />
      </div>
    </>
  );
};

export default LLMJudgeRuleDetails;

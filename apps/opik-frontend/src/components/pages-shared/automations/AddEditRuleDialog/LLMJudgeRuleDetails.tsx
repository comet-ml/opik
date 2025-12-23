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
import {
  generateDefaultLLMPromptMessage,
  getAllTemplateStringsFromContent,
} from "@/lib/llm";
import { COMPOSED_PROVIDER_TYPE, PROVIDER_MODEL_TYPE } from "@/types/providers";
import { safelyGetPromptMustacheTags } from "@/lib/prompt";
import { EvaluationRuleFormType } from "@/components/pages-shared/automations/AddEditRuleDialog/schema";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { updateProviderConfig } from "@/lib/modelUtils";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";

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
  projectName?: string;
  datasetColumnNames?: string[];
};

const LLMJudgeRuleDetails: React.FC<LLMJudgeRuleDetailsProps> = ({
  workspaceName,
  form,
  projectName,
  datasetColumnNames,
}) => {
  const cache = useRef<Record<string | LLM_JUDGE, LLMPromptTemplate>>({});
  const { calculateModelProvider, calculateDefaultModel } =
    useLLMProviderModelsData();

  const scope = form.watch("scope");
  const isThreadScope = scope === EVALUATORS_RULE_SCOPE.thread;
  const isSpanScope = scope === EVALUATORS_RULE_SCOPE.span;

  const templates = LLM_PROMPT_TEMPLATES[scope];

  // Determine the type for autocomplete based on scope
  const autocompleteType = isSpanScope
    ? TRACE_DATA_TYPE.spans
    : TRACE_DATA_TYPE.traces;

  const handleAddProvider = useCallback(
    (provider: COMPOSED_PROVIDER_TYPE) => {
      const model =
        (form.watch("llmJudgeDetails.model") as PROVIDER_MODEL_TYPE) || "";

      if (!model) {
        form.setValue(
          "llmJudgeDetails.model",
          calculateDefaultModel(model, [provider], provider),
        );
      }
    },
    [calculateDefaultModel, form],
  );

  const handleDeleteProvider = useCallback(
    (provider: COMPOSED_PROVIDER_TYPE) => {
      const model =
        (form.watch("llmJudgeDetails.model") as PROVIDER_MODEL_TYPE) || "";
      const currentProvider = calculateModelProvider(model);
      if (currentProvider === provider) {
        form.setValue("llmJudgeDetails.model", "");
      }
    },
    [calculateModelProvider, form],
  );

  // Memoized callback to handle messages change
  const handleMessagesChange = useCallback(
    (
      messages: LLMMessage[],
      fieldOnChange: (messages: LLMMessage[]) => void,
      formInstance: UseFormReturn<EvaluationRuleFormType>,
    ) => {
      fieldOnChange(messages);

      // recalculate variables
      const variables = formInstance.getValues("llmJudgeDetails.variables");
      const localVariables: Record<string, string> = {};
      let parsingVariablesError: boolean = false;
      messages
        .reduce<string[]>((acc, m) => {
          // Extract template strings from both text and image URLs
          const templateStrings = getAllTemplateStringsFromContent(m.content);
          // Get mustache tags from all template strings
          const allTags = templateStrings.flatMap((str) => {
            const tags = safelyGetPromptMustacheTags(str);
            if (!tags) {
              parsingVariablesError = true;
              return [];
            }
            return tags;
          });
          return acc.concat(allTags);
        }, [])
        .filter((v) => v !== "")
        .forEach((v: string) => (localVariables[v] = variables[v] ?? ""));

      formInstance.setValue("llmJudgeDetails.variables", localVariables);
      formInstance.setValue(
        "llmJudgeDetails.parsingVariablesError",
        parsingVariablesError,
      );
    },
    [],
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
                        // Update config to ensure reasoning models have temperature >= 1.0
                        const newProvider = calculateModelProvider(m);
                        const currentConfig = form.getValues(
                          "llmJudgeDetails.config",
                        );
                        const adjustedConfig = updateProviderConfig(
                          currentConfig,
                          { model: m, provider: newProvider },
                        );
                        if (
                          adjustedConfig &&
                          adjustedConfig !== currentConfig
                        ) {
                          form.setValue(
                            "llmJudgeDetails.config",
                            adjustedConfig,
                          );
                        }
                      }
                    }}
                    provider={provider}
                    hasError={Boolean(validationErrors?.message)}
                    workspaceName={workspaceName}
                    onAddProvider={handleAddProvider}
                    onDeleteProvider={handleDeleteProvider}
                  />

                  <FormField
                    control={form.control}
                    name="llmJudgeDetails.config"
                    render={({ field }) => (
                      <PromptModelConfigs
                        size="icon"
                        provider={provider}
                        model={model}
                        configs={field.value}
                        onChange={(partialConfig) => {
                          field.onChange({ ...field.value, ...partialConfig });
                        }}
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
            <Label>
              Prompt{" "}
              <ExplainerIcon
                className="inline"
                {...EXPLAINERS_MAP[EXPLAINER_ID.whats_that_prompt_select]}
              />
            </Label>
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
                      find(templates, (t) => t.value === newTemplate);

                    form.setValue(
                      "llmJudgeDetails.messages",
                      templateData.messages,
                    );
                    form.setValue(
                      "llmJudgeDetails.variables",
                      templateData.variables ?? {},
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
                options={templates}
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
                  disableMedia={isThreadScope}
                  promptVariables={datasetColumnNames}
                  onChange={(messages: LLMMessage[]) =>
                    handleMessagesChange(messages, field.onChange, form)
                  }
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
        {!isThreadScope && (
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
                    projectId={form.watch("projectIds")[0] || ""}
                    variables={field.value}
                    onChange={field.onChange}
                    projectName={projectName}
                    datasetColumnNames={datasetColumnNames}
                    type={autocompleteType}
                    includeIntermediateNodes
                  />
                </>
              );
            }}
          />
        )}
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

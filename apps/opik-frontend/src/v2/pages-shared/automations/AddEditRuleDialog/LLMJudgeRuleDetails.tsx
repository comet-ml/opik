import React, { useCallback, useMemo, useRef } from "react";
import { UseFormReturn } from "react-hook-form";
import { Info } from "lucide-react";
import find from "lodash/find";
import get from "lodash/get";

import { Label } from "@/ui/label";
import { FormControl, FormField, FormItem, FormMessage } from "@/ui/form";
import PromptModelSelect from "@/v2/pages-shared/llm/PromptModelSelect/PromptModelSelect";
import PromptModelConfigs from "@/v2/pages-shared/llm/PromptModelSettings/PromptModelConfigs";
import SelectBox from "@/shared/SelectBox/SelectBox";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import LLMPromptMessages from "@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import LLMJudgeScores from "@/v2/pages-shared/llm/LLMJudgeScores/LLMJudgeScores";
import {
  LLM_MESSAGE_ROLE_NAME_MAP,
  LLM_PROMPT_TEMPLATES,
} from "@/constants/llm";
import { LLM_JUDGE, LLM_MESSAGE_ROLE, LLMPromptTemplate } from "@/types/llm";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import { COMPOSED_PROVIDER_TYPE, PROVIDER_MODEL_TYPE } from "@/types/providers";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/AddEditRuleDialog/schema";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { updateProviderConfig } from "@/lib/modelUtils";
import useTracesList from "@/api/traces/useTracesList";
import useSpansList from "@/api/traces/useSpansList";
import { JsonObject, JsonValue } from "@/types/shared";

const isPlainObject = (v: unknown): v is Record<string, unknown> =>
  v !== null && typeof v === "object" && !Array.isArray(v);

const deepMergeSchemas = (
  target: JsonObject,
  source: JsonObject,
): JsonObject => {
  const result = { ...target };
  for (const key of Object.keys(source)) {
    const srcVal = source[key];
    const tgtVal = result[key];
    if (isPlainObject(srcVal) && isPlainObject(tgtVal)) {
      result[key] = deepMergeSchemas(
        tgtVal as JsonObject,
        srcVal as JsonObject,
      );
    } else if (
      !(key in result) ||
      result[key] === undefined ||
      result[key] === null
    ) {
      result[key] = srcVal as JsonValue;
    }
  }
  return result;
};

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
  datasetColumnNames?: string[];
};

const LLMJudgeRuleDetails: React.FC<LLMJudgeRuleDetailsProps> = ({
  workspaceName,
  form,
  datasetColumnNames,
}) => {
  const cache = useRef<Record<string | LLM_JUDGE, LLMPromptTemplate>>({});
  const { calculateModelProvider, calculateDefaultModel } =
    useLLMProviderModelsData();

  const scope = form.watch("scope");
  const isThreadScope = scope === EVALUATORS_RULE_SCOPE.thread;
  const projectId = form.watch("projectIds")[0] || "";

  const templates = LLM_PROMPT_TEMPLATES[scope];

  const isSpanScope = scope === EVALUATORS_RULE_SCOPE.span;

  const { data: tracesData } = useTracesList(
    {
      projectId,
      page: 1,
      size: 20,
      truncate: true,
    },
    { enabled: !!projectId && !isSpanScope },
  );

  const { data: spansData } = useSpansList(
    {
      projectId,
      page: 1,
      size: 20,
      truncate: true,
    },
    { enabled: !!projectId && isSpanScope },
  );

  const jsonTreeData: JsonObject | null = useMemo(() => {
    if (isThreadScope) {
      return {
        context: "(full conversation history)" as unknown as JsonObject,
      };
    }

    const items = isSpanScope ? spansData?.content : tracesData?.content;

    if (items && items.length > 0) {
      const merged: JsonObject = {
        input: {} as JsonObject,
        output: {} as JsonObject,
        metadata: {} as JsonObject,
      };
      for (const item of items) {
        for (const section of ["input", "output", "metadata"] as const) {
          const data = item[section];
          if (data && typeof data === "object") {
            merged[section] = deepMergeSchemas(
              merged[section] as JsonObject,
              data as JsonObject,
            );
          }
        }
      }
      if (!isSpanScope) {
        merged.spans = "(child spans of the trace)" as unknown as JsonObject;
      }
      return merged;
    }

    const fallback: JsonObject = {
      input: "(no data yet)" as unknown as JsonObject,
      output: "(no data yet)" as unknown as JsonObject,
      metadata: "(no data yet)" as unknown as JsonObject,
    };
    if (!isSpanScope) {
      fallback.spans = "(child spans of the trace)" as unknown as JsonObject;
    }
    return fallback;
  }, [tracesData, spansData, isThreadScope, isSpanScope]);

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
                  const { messages, schema, template } =
                    form.getValues("llmJudgeDetails");
                  if (newTemplate !== template) {
                    cache.current[template] = {
                      ...cache.current[template],
                      messages: messages,
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
              <LLMPromptMessages
                messages={messages}
                validationErrors={validationErrors}
                possibleTypes={MESSAGE_TYPE_OPTIONS}
                disableMedia={isThreadScope}
                promptVariables={datasetColumnNames}
                onChange={field.onChange}
                onAddMessage={() =>
                  field.onChange([
                    ...messages,
                    generateDefaultLLMPromptMessage({
                      role: LLM_MESSAGE_ROLE.user,
                    }),
                  ])
                }
                jsonTreeData={jsonTreeData}
              />
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

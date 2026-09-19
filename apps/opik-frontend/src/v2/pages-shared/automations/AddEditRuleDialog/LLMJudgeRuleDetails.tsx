import React, { useCallback, useRef } from "react";
import { UseFormReturn } from "react-hook-form";
import { Info } from "lucide-react";
import find from "lodash/find";
import get from "lodash/get";

import { Label } from "@/ui/label";
import {
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormMessage,
} from "@/ui/form";
import { Tag } from "@/ui/tag";
import { Description } from "@/ui/description";
import PromptModelSelect from "@/v2/pages-shared/llm/PromptModelSelect/PromptModelSelect";
import PromptModelConfigs from "@/v2/pages-shared/llm/PromptModelSettings/PromptModelConfigs";
import { RULE_UNSUPPORTED_PARAMS } from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";
import SelectBox from "@/shared/SelectBox/SelectBox";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import LLMPromptMessages from "@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import LLMPromptMessagesVariables from "@/v2/pages-shared/llm/LLMPromptMessagesVariables/LLMPromptMessagesVariables";
import LLMJudgeScores from "@/v2/pages-shared/llm/LLMJudgeScores/LLMJudgeScores";
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
  resolveTraceEvaluatorVariableDefault,
} from "@/lib/llm";
import { COMPOSED_PROVIDER_TYPE, PROVIDER_MODEL_TYPE } from "@/types/providers";
import { safelyGetPromptMustacheTags } from "@/lib/prompt";
import {
  RESERVED_SPAN_LLM_JUDGE_VARIABLES,
  RESERVED_TRACE_LLM_JUDGE_VARIABLES,
} from "@/constants/llm";
import {
  EvaluationRuleFormType,
  THREAD_CONTEXT_VARIABLE,
} from "@/v2/pages-shared/automations/AddEditRuleDialog/schema";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";
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
  datasetColumnNames?: string[];
};

/**
 * Thread rules have no variable mapping: the single {{context}} placeholder is
 * filled by the backend. Explain that where the mapping section would be, so
 * the first time a user learns about {{context}} is not a submit-time error.
 */
const ThreadContextInput: React.FC = () => (
  <div className="pt-4" data-testid="llm-judge-thread-context-input">
    <div className="comet-body-s-accented mb-1 text-muted-slate">
      Conversation input
    </div>
    <div className="flex items-start gap-3 rounded-md border border-border p-3">
      <Tag variant="green" size="md" className="mt-0.5 shrink-0">
        {THREAD_CONTEXT_VARIABLE}
      </Tag>
      <Description>
        Your prompt must include {THREAD_CONTEXT_VARIABLE} once. Opik replaces
        it with the whole thread as a list of user and assistant turns, oldest
        first; assistant turns can include the spans that produced them. Very
        long threads are passed as a compact per-trace summary the judge can
        inspect with tools. No mapping is needed and no other variables are
        available.
      </Description>
    </div>
  </div>
);

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
  const isSpanScope = scope === EVALUATORS_RULE_SCOPE.span;

  const templates = LLM_PROMPT_TEMPLATES[scope];
  // Span scope ships a single (custom) template — a one-option picker is noise.
  const hasTemplateChoice = templates.length > 1;

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
      const currentProvider = calculateModelProvider(model, provider);
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
      const currentScope = formInstance.getValues("scope");
      // {{span}} is reserved on span scope; {{trace}} / {{spans}} on trace scope.
      const reservedVariables =
        currentScope === EVALUATORS_RULE_SCOPE.span
          ? RESERVED_SPAN_LLM_JUDGE_VARIABLES
          : RESERVED_TRACE_LLM_JUDGE_VARIABLES;
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
        .forEach((v: string) => {
          localVariables[v] = resolveTraceEvaluatorVariableDefault(
            v,
            variables[v],
            currentScope,
            reservedVariables,
          );
        });

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
                    onChange={(m, selectedProvider) => {
                      if (m) {
                        field.onChange(m);
                        // Update config to ensure reasoning models have temperature >= 1.0
                        const currentConfig = form.getValues(
                          "llmJudgeDetails.config",
                        );
                        const adjustedConfig = updateProviderConfig(
                          currentConfig,
                          { model: m, provider: selectedProvider },
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
                        unsupportedParams={RULE_UNSUPPORTED_PARAMS}
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
            {hasTemplateChoice && (
              <>
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
                <FormDescription className="comet-body-xs text-muted-slate">
                  Picking a template replaces the prompt and score definition
                  below. Edits you make to each template are kept while this
                  dialog is open.
                </FormDescription>
              </>
            )}
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
        {isThreadScope ? (
          <ThreadContextInput />
        ) : (
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
                    datasetColumnNames={datasetColumnNames}
                    type={autocompleteType}
                    includeIntermediateNodes
                    reservedSentinels={
                      isSpanScope
                        ? RESERVED_SPAN_LLM_JUDGE_VARIABLES
                        : RESERVED_TRACE_LLM_JUDGE_VARIABLES
                    }
                  />
                </>
              );
            }}
          />
        )}
      </div>
      <div className="flex flex-col gap-2">
        <div className="flex items-center">
          <Label>Score definition</Label>
          <TooltipWrapper
            content={`Each entry becomes a feedback score returned by this rule,
under the name you give it. The judge is asked for
these scores automatically — you do not need to
describe the output format in the prompt.`}
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

import React, { useCallback, useMemo } from "react";
import { UseFormReturn } from "react-hook-form";
import { FileText } from "lucide-react";
import find from "lodash/find";
import get from "lodash/get";

import { Button } from "@/ui/button";
import { FormErrorSkeleton, FormField } from "@/ui/form";
import { Tag } from "@/ui/tag";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import PromptModelSelect from "@/v2/pages-shared/llm/PromptModelSelect/PromptModelSelect";
import PromptModelConfigs from "@/v2/pages-shared/llm/PromptModelSettings/PromptModelConfigs";
import { RULE_UNSUPPORTED_PARAMS } from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import LLMPromptMessages from "@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import LLMPromptMessagesVariables from "@/v2/pages-shared/llm/LLMPromptMessagesVariables/LLMPromptMessagesVariables";
import {
  LLM_MESSAGE_ROLE_NAME_MAP,
  LLM_PROMPT_TEMPLATES,
  RESERVED_SPAN_LLM_JUDGE_VARIABLES,
  RESERVED_TRACE_LLM_JUDGE_VARIABLES,
} from "@/constants/llm";
import {
  LLM_JUDGE,
  LLM_MESSAGE_ROLE,
  LLMMessage,
  LLMPromptTemplate,
  MessageContent,
} from "@/types/llm";
import {
  generateDefaultLLMPromptMessage,
  getAllTemplateStringsFromContent,
  getTextFromMessageContent,
  isInlineEntityPath,
  resolveTraceEvaluatorVariableDefault,
} from "@/lib/llm";
import {
  COMPOSED_PROVIDER_TYPE,
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
} from "@/types/providers";
import { safelyGetPromptMustacheTags } from "@/lib/prompt";
import { updateProviderConfig } from "@/lib/modelUtils";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import {
  EvaluationRuleFormType,
  THREAD_CONTEXT_VARIABLE,
} from "@/v2/pages-shared/automations/AddEditRuleDialog/schema";
import { getTemplatePresentation } from "@/v2/pages-shared/automations/AddEditRuleDialog/templatePresentation";
import { useEntitySampleJson } from "@/v2/pages-shared/automations/AddEditRuleDialog/useEntitySampleJson";

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
  projectId: string;
  form: UseFormReturn<EvaluationRuleFormType>;
  datasetColumnNames?: string[];
};

const TemplateBadge: React.FC<{ template: LLMPromptTemplate }> = ({
  template,
}) => {
  const { Icon, variant } = getTemplatePresentation(template.value);
  return (
    <Tag variant={variant} size="sm" className="shrink-0 px-1">
      <Icon className="size-3" />
    </Tag>
  );
};

const LLMJudgeRuleDetails: React.FC<LLMJudgeRuleDetailsProps> = ({
  workspaceName,
  projectId,
  form,
  datasetColumnNames,
}) => {
  const { calculateModelProvider, calculateDefaultModel } =
    useLLMProviderModelsData();

  const scope = form.watch("scope");
  const messages = form.watch("llmJudgeDetails.messages");
  const variables = form.watch("llmJudgeDetails.variables");
  const isThreadScope = scope === EVALUATORS_RULE_SCOPE.thread;
  const isSpanScope = scope === EVALUATORS_RULE_SCOPE.span;

  const templates = useMemo(
    () =>
      LLM_PROMPT_TEMPLATES[scope].filter((t) => t.value !== LLM_JUDGE.custom),
    [scope],
  );
  const isPromptEmpty = messages.every(
    (m) => !getTextFromMessageContent(m.content).trim(),
  );

  const sampleJson = useEntitySampleJson({
    projectId,
    scope,
    datasetColumnNames,
  });

  const reservedVariables = isSpanScope
    ? RESERVED_SPAN_LLM_JUDGE_VARIABLES
    : RESERVED_TRACE_LLM_JUDGE_VARIABLES;

  // Rows hidden from the "Variable sources" list: reserved sentinels and
  // inline paths, which resolve on their own. Whatever remains needs a source.
  const selfResolvedSentinels = useMemo(
    () => ({
      ...reservedVariables,
      ...Object.fromEntries(
        Object.keys(variables ?? {})
          .filter(isInlineEntityPath)
          .map((name) => [name, name]),
      ),
    }),
    [reservedVariables, variables],
  );
  const unmappedCount = Object.entries(variables ?? {}).filter(
    ([name, value]) => selfResolvedSentinels[name] !== value,
  ).length;

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

  const handleMessagesChange = useCallback(
    (nextMessages: LLMMessage[]) => {
      form.setValue("llmJudgeDetails.messages", nextMessages, {
        shouldDirty: true,
      });

      const currentVariables = form.getValues("llmJudgeDetails.variables");
      const currentScope = form.getValues("scope");
      const reserved =
        currentScope === EVALUATORS_RULE_SCOPE.span
          ? RESERVED_SPAN_LLM_JUDGE_VARIABLES
          : RESERVED_TRACE_LLM_JUDGE_VARIABLES;
      const localVariables: Record<string, string> = {};
      let parsingVariablesError = false;
      nextMessages
        .reduce<string[]>((acc, m) => {
          const templateStrings = getAllTemplateStringsFromContent(m.content);
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
            currentVariables[v],
            currentScope,
            reserved,
          );
        });

      form.setValue("llmJudgeDetails.variables", localVariables);
      form.setValue(
        "llmJudgeDetails.parsingVariablesError",
        parsingVariablesError,
      );
    },
    [form],
  );

  const applyTemplate = useCallback(
    (value: string) => {
      const template = find(templates, (t) => t.value === value);
      if (!template) return;
      form.setValue("llmJudgeDetails.template", template.value);
      form.setValue("llmJudgeDetails.schema", template.schema);
      form.setValue("llmJudgeDetails.variables", template.variables ?? {});
      handleMessagesChange(
        template.messages.map((m) => generateDefaultLLMPromptMessage(m)),
      );
    },
    [form, handleMessagesChange, templates],
  );

  const model = form.watch("llmJudgeDetails.model") as PROVIDER_MODEL_TYPE | "";
  const provider = calculateModelProvider(model);
  const modelError = get(form.formState.errors, [
    "llmJudgeDetails",
    "model",
    "message",
  ]) as string | undefined;

  const improvePromptConfig = useMemo(
    () => ({
      model,
      provider,
      configs: form.getValues(
        "llmJudgeDetails.config",
      ) as unknown as LLMPromptConfigsType,
      workspaceName,
      onAccept: (messageId: string, improvedContent: MessageContent) =>
        handleMessagesChange(
          form
            .getValues("llmJudgeDetails.messages")
            .map((m) =>
              m.id === messageId ? { ...m, content: improvedContent } : m,
            ),
        ),
    }),
    [model, provider, form, workspaceName, handleMessagesChange],
  );

  return (
    <div className="flex flex-col gap-2">
      <div
        className="flex min-h-[420px] flex-col overflow-hidden rounded-md border border-border"
        data-testid="llm-judge-prompt-card"
      >
        <div className="flex h-10 shrink-0 items-center justify-between gap-2 border-b border-border bg-soft-background px-2">
          <div className="flex min-w-0 items-center gap-1">
            <PromptModelSelect
              compact
              value={model}
              onChange={(m, selectedProvider) => {
                if (!m) return;
                form.setValue("llmJudgeDetails.model", m, {
                  shouldValidate: true,
                });
                const currentConfig = form.getValues("llmJudgeDetails.config");
                const adjustedConfig = updateProviderConfig(currentConfig, {
                  model: m,
                  provider: selectedProvider,
                });
                if (adjustedConfig && adjustedConfig !== currentConfig) {
                  form.setValue("llmJudgeDetails.config", adjustedConfig);
                }
              }}
              provider={provider}
              hasError={Boolean(modelError)}
              workspaceName={workspaceName}
              onAddProvider={handleAddProvider}
              onDeleteProvider={handleDeleteProvider}
            />
            <FormField
              control={form.control}
              name="llmJudgeDetails.config"
              render={({ field }) => (
                <PromptModelConfigs
                  size="icon-2xs"
                  variant="minimal"
                  provider={provider}
                  model={model}
                  configs={field.value}
                  unsupportedParams={RULE_UNSUPPORTED_PARAMS}
                  onChange={(partialConfig) => {
                    field.onChange({ ...field.value, ...partialConfig });
                  }}
                />
              )}
            />
          </div>
          {templates.length > 0 && (
            <DropdownMenu>
              <TooltipWrapper content="Start from a template">
                <DropdownMenuTrigger asChild>
                  <Button
                    variant="minimal"
                    size="icon-2xs"
                    type="button"
                    aria-label="Prompt templates"
                    data-testid="llm-judge-template-menu"
                  >
                    <FileText />
                  </Button>
                </DropdownMenuTrigger>
              </TooltipWrapper>
              <DropdownMenuContent align="end" className="w-80 p-1">
                {templates.map((template) => (
                  <DropdownMenuItem
                    key={template.value}
                    onClick={() => applyTemplate(template.value)}
                    className="h-auto items-start gap-2 py-2"
                  >
                    <TemplateBadge template={template} />
                    <div className="flex min-w-0 flex-col gap-0.5">
                      <span className="comet-body-s-accented">
                        {template.label}
                      </span>
                      <span className="comet-body-xs whitespace-normal text-muted-slate">
                        {template.description}
                      </span>
                    </div>
                  </DropdownMenuItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
          )}
        </div>
        {modelError && (
          <FormErrorSkeleton className="mx-2 mt-2">
            {modelError}
          </FormErrorSkeleton>
        )}
        <div className="flex flex-1 flex-col gap-2 p-2">
          <FormField
            control={form.control}
            name="llmJudgeDetails.messages"
            render={({ field, formState }) => (
              <LLMPromptMessages
                messages={field.value}
                validationErrors={get(formState.errors, [
                  "llmJudgeDetails",
                  "messages",
                ])}
                possibleTypes={MESSAGE_TYPE_OPTIONS}
                disableMedia={isThreadScope}
                hidePromptActions={false}
                improvePromptConfig={improvePromptConfig}
                promptVariables={datasetColumnNames}
                jsonTreeData={sampleJson}
                onChange={handleMessagesChange}
                onAddMessage={() =>
                  handleMessagesChange([
                    ...field.value,
                    generateDefaultLLMPromptMessage({
                      role: LLM_MESSAGE_ROLE.user,
                    }),
                  ])
                }
              />
            )}
          />
          {/* Templates only offer a starting point: once anything is typed they get out of the way. */}
          {isPromptEmpty && templates.length > 0 && (
            <div
              className="mt-auto flex flex-col gap-2 pt-2"
              data-testid="llm-judge-template-chips"
            >
              <span className="comet-body-s text-muted-slate">
                Or get started with a template
              </span>
              <div className="flex flex-wrap gap-2">
                {templates.map((template) => (
                  <Button
                    key={template.value}
                    variant="outline"
                    size="xs"
                    type="button"
                    className="gap-1.5 px-1.5"
                    onClick={() => applyTemplate(template.value)}
                  >
                    <TemplateBadge template={template} />
                    {template.label}
                  </Button>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
      <span className="comet-body-s text-light-slate">
        {isThreadScope ? (
          <>
            Use {THREAD_CONTEXT_VARIABLE} once to insert the whole thread as
            user and assistant turns. It is the only variable available for
            thread rules.
          </>
        ) : (
          <>
            Use{" "}
            <Tag variant="green" size="sm" className="px-1">
              {"{{"}
            </Tag>{" "}
            to insert variables, which will automatically pull data from{" "}
            {isSpanScope ? "spans" : "traces"} when the rule runs.
          </>
        )}
      </span>
      {!isThreadScope && unmappedCount > 0 && (
        <FormField
          control={form.control}
          name="llmJudgeDetails.variables"
          render={({ field, formState }) => (
            <LLMPromptMessagesVariables
              parsingError={form.getValues(
                "llmJudgeDetails.parsingVariablesError",
              )}
              validationErrors={get(formState.errors, [
                "llmJudgeDetails",
                "variables",
              ])}
              projectId={projectId}
              variables={field.value}
              onChange={field.onChange}
              description={`These variables are not ${
                isSpanScope ? "span" : "trace"
              } field paths, so pick the field each one should read from.`}
              datasetColumnNames={datasetColumnNames}
              type={
                isSpanScope ? TRACE_DATA_TYPE.spans : TRACE_DATA_TYPE.traces
              }
              includeIntermediateNodes
              reservedSentinels={selfResolvedSentinels}
            />
          )}
        />
      )}
    </div>
  );
};

export default LLMJudgeRuleDetails;

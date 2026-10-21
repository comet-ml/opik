import React, { useCallback, useMemo, useState } from "react";
import { UseFormReturn } from "react-hook-form";
import { FileText, MessageCircleWarning, X } from "lucide-react";
import find from "lodash/find";
import get from "lodash/get";

import { Alert, AlertDescription } from "@/ui/alert";
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
import IconBadge from "@/shared/IconBadge/IconBadge";
import LLMPromptMessages from "@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import {
  LLM_MESSAGE_ROLE_NAME_MAP,
  LLM_PROMPT_TEMPLATES,
  RESERVED_SPAN_LLM_JUDGE_VARIABLES,
  RESERVED_TRACE_LLM_JUDGE_VARIABLES,
} from "@/constants/llm";
import { DECISION_MODELS } from "@/constants/decisionModels";
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
  resolveTraceEvaluatorVariableDefault,
} from "@/lib/llm";
import {
  COMPOSED_PROVIDER_TYPE,
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
  ProviderModelsMap,
} from "@/types/providers";
import { safelyGetPromptMustacheTags } from "@/lib/prompt";
import { isDecisionModel } from "@/lib/modelCapabilities";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import {
  EvaluationRuleFormType,
  THREAD_CONTEXT_VARIABLE,
  updateConfigForModelChange,
} from "@/v2/pages-shared/automations/AddEditRuleDialog/schema";
import {
  fromDecisionModelDetails,
  getDecisionModelReservedVariables,
  toDecisionModelDetails,
} from "@/v2/pages-shared/automations/AddEditRuleDialog/decisionModelRule";
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

// Decisions models (Jev) take a single user message.
const DECISION_MODEL_MESSAGE_TYPE_OPTIONS = MESSAGE_TYPE_OPTIONS.filter(
  (option) => option.value === LLM_MESSAGE_ROLE.user,
);

const DECISION_MODEL_PROVIDER_MODELS: ProviderModelsMap = {
  [PROVIDER_TYPE.OPEN_ROUTER]: DECISION_MODELS,
};

type LLMJudgeRuleDetailsProps = {
  workspaceName: string;
  projectId?: string;
  form: UseFormReturn<EvaluationRuleFormType>;
  datasetColumnNames?: string[];
};

const TemplateBadge: React.FC<{
  template: LLMPromptTemplate;
  className?: string;
}> = ({ template, className }) => {
  const { Icon, color } = getTemplatePresentation(template.value);
  return <IconBadge Icon={Icon} color={color} className={className} />;
};

const LLMJudgeRuleDetails: React.FC<LLMJudgeRuleDetailsProps> = ({
  workspaceName,
  projectId = "",
  form,
  datasetColumnNames,
}) => {
  const { calculateModelProvider, calculateDefaultModel } =
    useLLMProviderModelsData();

  // What switching to a decisions model changed in the form, shown so the switch is never silent.
  const [decisionModelNotes, setDecisionModelNotes] = useState<string[]>([]);

  const scope = form.watch("scope");
  const messages = form.watch("llmJudgeDetails.messages");
  const currentTemplate = form.watch("llmJudgeDetails.template");
  const model = form.watch("llmJudgeDetails.model") as PROVIDER_MODEL_TYPE | "";
  const isThreadScope = scope === EVALUATORS_RULE_SCOPE.thread;
  const isSpanScope = scope === EVALUATORS_RULE_SCOPE.span;
  const isDecision = isDecisionModel(model);

  // Jev has only its own Custom template, applied on the switch, so it gets no chips or menu.
  const templates = useMemo(
    () =>
      isDecision
        ? []
        : LLM_PROMPT_TEMPLATES[scope].filter(
            (t) => t.value !== LLM_JUDGE.custom,
          ),
    [scope, isDecision],
  );
  const appliedTemplate = find(templates, (t) => t.value === currentTemplate);
  const isPromptEmpty = messages.every(
    (m) => !getTextFromMessageContent(m.content).trim(),
  );

  // Thread rules don't support decisions models.
  const extraProviderModels = !isThreadScope
    ? DECISION_MODEL_PROVIDER_MODELS
    : undefined;

  const sampleJson = useEntitySampleJson({
    projectId,
    scope,
    datasetColumnNames,
  });

  // Adapts the form when switching to a decisions model (see toDecisionModelDetails) and keeps what it changed.
  const handleSwitchToDecisionModel = useCallback(() => {
    const { template, messages, variables, schema } =
      form.getValues("llmJudgeDetails");
    const adapted = toDecisionModelDetails(
      { template, messages, variables, schema },
      form.getValues("scope"),
    );

    form.setValue("llmJudgeDetails.template", adapted.template);
    form.setValue("llmJudgeDetails.messages", adapted.messages);
    form.setValue("llmJudgeDetails.variables", adapted.variables);
    form.setValue("llmJudgeDetails.schema", adapted.schema);
    setDecisionModelNotes(adapted.notes);
  }, [form]);

  // Undoes the template part of the switch when going back to a chat model (see fromDecisionModelDetails).
  const handleSwitchFromDecisionModel = useCallback(() => {
    const { template, messages, variables, schema } =
      form.getValues("llmJudgeDetails");
    const restored = fromDecisionModelDetails(
      { template, messages, variables, schema },
      form.getValues("scope"),
    );
    if (restored) {
      form.setValue("llmJudgeDetails.template", restored.template);
      form.setValue("llmJudgeDetails.messages", restored.messages);
      form.setValue("llmJudgeDetails.variables", restored.variables);
      form.setValue("llmJudgeDetails.schema", restored.schema);
    }
    setDecisionModelNotes([]);
  }, [form]);

  // Every model change goes through here, so leaving or entering Jev adapts the prompt on each path: the
  // selector, and the provider callbacks that clear or default the model.
  const handleModelTransition = useCallback(
    (previousModel: string, nextModel: string) => {
      const wasDecision = isDecisionModel(previousModel);
      if (isDecisionModel(nextModel)) {
        if (!wasDecision) {
          handleSwitchToDecisionModel();
        }
      } else if (wasDecision) {
        handleSwitchFromDecisionModel();
      }
    },
    [handleSwitchFromDecisionModel, handleSwitchToDecisionModel],
  );

  const handleAddProvider = useCallback(
    (provider: COMPOSED_PROVIDER_TYPE) => {
      const currentModel =
        (form.watch("llmJudgeDetails.model") as PROVIDER_MODEL_TYPE) || "";

      if (!currentModel) {
        const defaultModel = calculateDefaultModel(
          currentModel,
          [provider],
          provider,
        );
        handleModelTransition(currentModel, defaultModel);
        form.setValue("llmJudgeDetails.model", defaultModel);
      }
    },
    [calculateDefaultModel, form, handleModelTransition],
  );

  const handleDeleteProvider = useCallback(
    (provider: COMPOSED_PROVIDER_TYPE) => {
      const currentModel =
        (form.watch("llmJudgeDetails.model") as PROVIDER_MODEL_TYPE) || "";
      const currentProvider = calculateModelProvider(currentModel, provider);
      if (currentProvider === provider) {
        handleModelTransition(currentModel, "");
        form.setValue("llmJudgeDetails.model", "");
        // With no model left, the next pick or added provider has no previous provider to compare
        // against, so updateConfigForModelChange could not tell the extra body belonged to this one.
        const config = form.getValues("llmJudgeDetails.config");
        if (config.custom_parameters != null) {
          form.setValue("llmJudgeDetails.config", {
            ...config,
            custom_parameters: null,
          });
        }
      }
    },
    [calculateModelProvider, form, handleModelTransition],
  );

  const handleMessagesChange = useCallback(
    (nextMessages: LLMMessage[]) => {
      form.setValue("llmJudgeDetails.messages", nextMessages, {
        shouldDirty: true,
      });

      const currentVariables = form.getValues("llmJudgeDetails.variables");
      const currentScope = form.getValues("scope");
      // {{span}} is reserved on span scope; {{trace}} / {{spans}} on trace scope.
      // Decisions models don't take {{trace}} / {{span}}, so those aren't auto-mapped to the sentinel.
      const reserved = isDecisionModel(form.getValues("llmJudgeDetails.model"))
        ? getDecisionModelReservedVariables(currentScope)
        : currentScope === EVALUATORS_RULE_SCOPE.span
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

  // Back to a blank prompt; the score definition resets to the scope's default.
  const removeTemplate = useCallback(() => {
    const custom = find(
      LLM_PROMPT_TEMPLATES[scope],
      (t) => t.value === LLM_JUDGE.custom,
    );
    form.setValue("llmJudgeDetails.template", LLM_JUDGE.custom);
    if (custom) form.setValue("llmJudgeDetails.schema", custom.schema);
    form.setValue("llmJudgeDetails.variables", {});
    handleMessagesChange([
      generateDefaultLLMPromptMessage({ role: LLM_MESSAGE_ROLE.user }),
    ]);
  }, [form, handleMessagesChange, scope]);

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
                const previousModel = model;
                form.setValue("llmJudgeDetails.model", m, {
                  shouldValidate: true,
                });
                handleModelTransition(previousModel, m);
                const currentConfig = form.getValues("llmJudgeDetails.config");
                // The picker reports the bare provider type ("custom-llm"), while `provider` is the
                // row's key ("custom-llm:mock"). Compared as is, two models of one Custom LLM row
                // would look like a provider change and lose the extra body.
                const adjustedConfig = updateConfigForModelChange(
                  currentConfig,
                  { model: previousModel, provider },
                  {
                    model: m,
                    provider:
                      calculateModelProvider(m, selectedProvider) ||
                      selectedProvider,
                  },
                );
                if (adjustedConfig !== currentConfig) {
                  form.setValue("llmJudgeDetails.config", adjustedConfig);
                }
              }}
              provider={provider}
              hasError={Boolean(modelError)}
              workspaceName={workspaceName}
              onAddProvider={handleAddProvider}
              onDeleteProvider={handleDeleteProvider}
              extraProviderModels={extraProviderModels}
            />
            {/* A decisions model takes no settings: no temperature, seed, thinking or custom parameters. */}
            {!isDecision && (
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
            )}
          </div>
          <div className="flex shrink-0 items-center gap-1">
            {appliedTemplate && (
              <div
                className="comet-body-xs flex h-6 items-center gap-1 rounded-sm border border-border bg-background pl-1.5 pr-0.5"
                data-testid="llm-judge-applied-template"
              >
                <TemplateBadge
                  template={appliedTemplate}
                  className="size-3.5"
                />
                <span className="truncate">{appliedTemplate.label}</span>
                <TooltipWrapper content="Remove template">
                  <Button
                    variant="minimal"
                    size="icon-3xs"
                    type="button"
                    aria-label="Remove template"
                    onClick={removeTemplate}
                  >
                    <X />
                  </Button>
                </TooltipWrapper>
              </div>
            )}
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
                      <TemplateBadge template={template} className="mt-0.5" />
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
        </div>
        {modelError && (
          <FormErrorSkeleton className="mx-2 mt-2">
            {modelError}
          </FormErrorSkeleton>
        )}
        {isDecision && decisionModelNotes.length > 0 && (
          <Alert size="sm" className="mx-2 mt-2">
            <MessageCircleWarning />
            <AlertDescription size="sm" className="flex flex-col gap-1">
              {decisionModelNotes.map((note) => (
                <span key={note}>{note}</span>
              ))}
            </AlertDescription>
          </Alert>
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
                possibleTypes={
                  isDecision
                    ? DECISION_MODEL_MESSAGE_TYPE_OPTIONS
                    : MESSAGE_TYPE_OPTIONS
                }
                disableMedia={isThreadScope || isDecision}
                hidePromptActions={false}
                hideAddButton
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
              <div className="flex flex-nowrap gap-1 overflow-x-auto pb-1">
                {templates.map((template) => (
                  <Button
                    key={template.value}
                    variant="outline"
                    size="2xs"
                    type="button"
                    className="shrink-0 gap-1 px-1"
                    onClick={() => applyTemplate(template.value)}
                  >
                    <TemplateBadge template={template} className="size-3.5" />
                    {template.label}
                  </Button>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
      {isDecision && (
        <span className="comet-body-s text-light-slate">
          Jev answers each score as a yes/no question about your prompt. The
          prompt is the text Jev reads; each score&apos;s description is its
          question. Ask one thing per score, phrased so that a high probability
          means yes. A score is 1 when the probability is 0.5 or higher, and the
          reason shows the probability.
        </span>
      )}
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
    </div>
  );
};

export default LLMJudgeRuleDetails;

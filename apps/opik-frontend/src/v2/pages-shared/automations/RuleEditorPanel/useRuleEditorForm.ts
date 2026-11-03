import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { FieldPathValue, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import cloneDeep from "lodash/cloneDeep";
import isEqual from "lodash/isEqual";
import omitBy from "lodash/omitBy";

import {
  EVAL_TRIGGER_SCOPE,
  EVALUATORS_RULE_SCOPE,
  EVALUATORS_RULE_TYPE,
  EvaluatorsRule,
  LLMJudgeObject,
  PythonCodeObject,
  UI_EVALUATORS_RULE_TYPE,
} from "@/types/automations";
import { ColumnData } from "@/types/shared";
import { isLLMJudgeRule, isPythonCodeRule } from "@/lib/rules";
import { LLMMessage, LLMPromptTemplate } from "@/types/llm";
import {
  RESERVED_SPAN_LLM_JUDGE_VARIABLES,
  RESERVED_TRACE_LLM_JUDGE_VARIABLES,
} from "@/constants/llm";
import {
  getAllTemplateStringsFromContent,
  resolveTraceEvaluatorVariableDefault,
} from "@/lib/llm";
import { safelyGetPromptMustacheTags } from "@/lib/prompt";
import useAppStore from "@/store/AppStore";
import useOpenAiPipelineMode from "@/hooks/useOpenAiPipelineMode";
import {
  convertLLMJudgeDataToLLMJudgeObject,
  convertLLMJudgeObjectToLLMJudgeData,
  EvaluationRuleFormSchema,
  EvaluationRuleFormType,
  updateConfigForModelChange,
} from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import { convertToStandardSignature } from "@/v2/pages-shared/automations/RuleEditorPanel/codeSignature";
import { RuleDefaultModel } from "@/v2/pages-shared/automations/RuleEditorPanel/useRuleDefaultModel";
import {
  buildRuleFilters,
  getBackendRuleType,
  getUIRuleScope,
  getCodeArguments,
  getRuleUIType,
  getPromptMergeInfo,
  getSavedCodeArguments,
  mergeMessagesIntoPrompt,
  normalizeFilters,
  withEmptyRuleFilter,
} from "@/v2/pages-shared/automations/RuleEditorPanel/helpers";
import {
  inlineVariableMappings,
  isInlinedMapping,
  nameInlineVariables,
} from "@/v2/pages-shared/automations/RuleEditorPanel/inlineVariableMappings";
import {
  DEFAULT_DECISION_MODEL_DATA,
  DEFAULT_LLM_AS_JUDGE_DATA,
  FILTER_COLUMNS_BY_SCOPE,
  DEFAULT_PYTHON_CODE_DATA,
  DEFAULT_SAMPLING_RATE,
} from "@/v2/pages-shared/automations/RuleEditorPanel/constants";

const DERIVED_FIELDS = [
  "llmJudgeDetails.variables",
  "llmJudgeDetails.parsingVariablesError",
  "pythonCodeDetails.arguments",
  "pythonCodeDetails.parsingArgumentsError",
];

const isDerivedField = (name: string) =>
  DERIVED_FIELDS.some(
    (field) => name === field || name.startsWith(`${field}.`),
  );

const ENTITY_PATH_REGEX = /^(input|output|metadata)(\.|$)/;

const getPromptVariables = (
  messages: LLMMessage[],
  currentVariables: Record<string, string>,
  scope: EVALUATORS_RULE_SCOPE,
) => {
  const reservedVariables =
    scope === EVALUATORS_RULE_SCOPE.span
      ? RESERVED_SPAN_LLM_JUDGE_VARIABLES
      : RESERVED_TRACE_LLM_JUDGE_VARIABLES;
  const variables: Record<string, string> = {};
  let parsingVariablesError = false;

  messages
    .flatMap((message) => getAllTemplateStringsFromContent(message.content))
    .flatMap((template): string[] => {
      const tags = safelyGetPromptMustacheTags(template);
      if (!tags) {
        parsingVariablesError = true;
        return [];
      }
      return tags;
    })
    .filter((tag) => tag !== "")
    .forEach((tag) => {
      const mapping = resolveTraceEvaluatorVariableDefault(
        tag,
        currentVariables[tag],
        scope,
        reservedVariables,
      );
      variables[tag] =
        mapping === "" &&
        scope !== EVALUATORS_RULE_SCOPE.thread &&
        ENTITY_PATH_REGEX.test(tag)
          ? tag
          : mapping;
    });

  return { variables, parsingVariablesError };
};

type SavedLLMJudgeData = ReturnType<typeof convertLLMJudgeObjectToLLMJudgeData>;

const toFormLLMJudgeDetails = (
  details: SavedLLMJudgeData,
  uiType: UI_EVALUATORS_RULE_TYPE,
) => ({
  ...details,
  messages: mergeMessagesIntoPrompt(
    inlineVariableMappings(details.messages, details.variables),
  ),
  variables: omitBy(details.variables, isInlinedMapping),
  schema:
    uiType === UI_EVALUATORS_RULE_TYPE.decision_model
      ? details.schema.map((score) => ({
          ...score,
          description: score.description.trim() || score.name,
        }))
      : details.schema,
});

const withDefaultModel = <
  T extends {
    model: string;
    config: Parameters<typeof updateConfigForModelChange>[0];
  },
>(
  details: T,
  { model, provider }: RuleDefaultModel,
): T =>
  details.model || !model || !provider
    ? details
    : {
        ...details,
        model,
        config: updateConfigForModelChange(
          details.config,
          { model: "", provider: "" },
          { model, provider },
        ),
      };

const getDefaultJudgeData = (uiType: UI_EVALUATORS_RULE_TYPE) =>
  uiType === UI_EVALUATORS_RULE_TYPE.decision_model
    ? DEFAULT_DECISION_MODEL_DATA
    : DEFAULT_LLM_AS_JUDGE_DATA;

const getDefaultValues = (
  ruleType: UI_EVALUATORS_RULE_TYPE,
  projectId: string | undefined,
  rule: EvaluatorsRule | undefined,
  isClone: boolean,
  defaultModel: RuleDefaultModel,
  savedJudge: SavedLLMJudgeData | undefined,
  defaultScope: EVALUATORS_RULE_SCOPE,
): EvaluationRuleFormType => {
  const uiType = rule ? getRuleUIType(rule) : ruleType;
  const scope = rule ? getUIRuleScope(rule.type) : defaultScope;
  const ruleProjectIds =
    isClone && projectId
      ? [projectId]
      : rule?.projects?.map((p) => p.project_id);

  return {
    ruleName: rule ? (isClone ? `${rule.name} (Copy)` : rule.name) : "",
    projectIds: ruleProjectIds?.length
      ? ruleProjectIds
      : projectId
        ? [projectId]
        : [],
    samplingRate: rule?.sampling_rate ?? DEFAULT_SAMPLING_RATE,
    uiType,
    scope,
    type: getBackendRuleType(scope, uiType),
    enabled: rule?.enabled ?? true,
    triggerScope: rule?.trigger_scope ?? EVAL_TRIGGER_SCOPE.production,
    filters: withEmptyRuleFilter(
      normalizeFilters(
        rule?.filters ?? [],
        FILTER_COLUMNS_BY_SCOPE[scope] as ColumnData<unknown>[],
      ),
    ),
    llmJudgeDetails: withDefaultModel(
      savedJudge
        ? toFormLLMJudgeDetails(savedJudge, uiType)
        : cloneDeep(getDefaultJudgeData(uiType)[scope]),
      defaultModel,
    ),
    pythonCodeDetails:
      rule && isPythonCodeRule(rule)
        ? (rule.code as PythonCodeObject)
        : cloneDeep(DEFAULT_PYTHON_CODE_DATA[scope]),
  } as EvaluationRuleFormType;
};

type ScopeDraftField =
  | "triggerScope"
  | "filters"
  | "llmJudgeDetails"
  | "pythonCodeDetails";

type ScopeDraft = {
  [K in ScopeDraftField]: FieldPathValue<EvaluationRuleFormType, K>;
} & { autoName: string };

type UseRuleEditorFormParams = {
  ruleType: UI_EVALUATORS_RULE_TYPE;
  projectId?: string;
  rule?: EvaluatorsRule;
  isClone?: boolean;
  defaultModel: RuleDefaultModel;
  defaultScope?: EVALUATORS_RULE_SCOPE;
};

const useRuleEditorForm = ({
  ruleType,
  projectId,
  rule,
  isClone = false,
  defaultModel,
  defaultScope = EVALUATORS_RULE_SCOPE.trace,
}: UseRuleEditorFormParams) => {
  const isEdit = Boolean(rule) && !isClone;
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const openAiPipelineMode = useOpenAiPipelineMode(workspaceName);
  const savedJudge = useMemo(
    () =>
      rule && isLLMJudgeRule(rule)
        ? convertLLMJudgeObjectToLLMJudgeData(rule.code as LLMJudgeObject)
        : undefined,
    [rule],
  );
  const [defaultValues] = useState(() =>
    getDefaultValues(
      ruleType,
      projectId,
      rule,
      isClone,
      defaultModel,
      savedJudge,
      defaultScope,
    ),
  );
  const form = useForm<EvaluationRuleFormType>({
    resolver: zodResolver(EvaluationRuleFormSchema),
    defaultValues,
  });
  const savedVariables = savedJudge?.variables;
  const promptMergeInfo = useMemo(
    () => (savedJudge ? getPromptMergeInfo(savedJudge.messages) : null),
    [savedJudge],
  );

  useEffect(() => {
    if (isEdit) return;
    let previousRuleName = form.getValues("ruleName").trim();
    const subscription = form.watch((values, { name }) => {
      if (name !== "ruleName") return;
      const ruleName = (values.ruleName ?? "").trim();
      const mainScoreName = form.getValues("llmJudgeDetails.schema.0.name");
      if (
        mainScoreName !== undefined &&
        (!mainScoreName.trim() || mainScoreName === previousRuleName)
      ) {
        form.setValue("llmJudgeDetails.schema.0.name", ruleName);
      }
      previousRuleName = ruleName;
    });
    return () => subscription.unsubscribe();
  }, [form, isEdit]);

  const scopeDraftsRef = useRef<
    Partial<Record<EVALUATORS_RULE_SCOPE, ScopeDraft>>
  >({});
  const autoNameRef = useRef("");

  const isDefaultName = useCallback(() => {
    const name = form.getValues("ruleName").trim();
    return !name || name === autoNameRef.current;
  }, [form]);

  const setAutoName = useCallback(
    (name: string) => {
      autoNameRef.current = name;
      form.setValue("ruleName", name, { shouldDirty: true });
    },
    [form],
  );

  const changeScope = useCallback(
    (scope: EVALUATORS_RULE_SCOPE) => {
      const uiType = form.getValues("uiType");
      const llmJudgeDetails = form.getValues("llmJudgeDetails");
      scopeDraftsRef.current[form.getValues("scope")] = cloneDeep({
        triggerScope: form.getValues("triggerScope"),
        filters: form.getValues("filters"),
        llmJudgeDetails,
        pythonCodeDetails: form.getValues("pythonCodeDetails"),
        autoName: autoNameRef.current,
      });
      const draft = scopeDraftsRef.current[scope];

      if (isDefaultName()) setAutoName(draft?.autoName ?? "");

      form.setValue("scope", scope);
      form.setValue("type", getBackendRuleType(scope, uiType));
      form.setValue(
        "triggerScope",
        draft?.triggerScope ?? EVAL_TRIGGER_SCOPE.production,
      );
      form.setValue("filters", draft?.filters ?? withEmptyRuleFilter([]));
      form.setValue("llmJudgeDetails", {
        ...(draft?.llmJudgeDetails ??
          cloneDeep(getDefaultJudgeData(uiType)[scope])),
        model: llmJudgeDetails.model,
        config: llmJudgeDetails.config,
      });
      form.setValue(
        "pythonCodeDetails",
        draft?.pythonCodeDetails ?? cloneDeep(DEFAULT_PYTHON_CODE_DATA[scope]),
      );
    },
    [form, isDefaultName, setAutoName],
  );

  const applyTemplate = useCallback(
    (template: LLMPromptTemplate, label: string) => {
      const current = form.getValues("llmJudgeDetails");
      const [templateMainScore, ...templateScores] = cloneDeep(template.schema);
      const mainScoreName = isEdit ? current.schema[0]?.name ?? "" : "";
      form.setValue(
        "llmJudgeDetails",
        {
          ...current,
          template: template.value,
          messages: cloneDeep(template.messages),
          variables: cloneDeep(template.variables),
          schema: templateMainScore
            ? [{ ...templateMainScore, name: mainScoreName }, ...templateScores]
            : [],
        },
        { shouldDirty: true },
      );
      form.clearErrors("llmJudgeDetails");
      if (!isEdit && isDefaultName()) setAutoName(label);
    },
    [form, isEdit, isDefaultName, setAutoName],
  );

  const prepareForSubmit = useCallback(() => {
    const values = form.getValues();

    if (values.uiType === UI_EVALUATORS_RULE_TYPE.python_code) {
      if (values.scope === EVALUATORS_RULE_SCOPE.thread) return;
      const details = form.getValues("pythonCodeDetails");
      const { metric } = details;
      const {
        arguments: codeArguments,
        unknownArguments,
        parsingError,
      } = getCodeArguments(
        metric,
        values.scope,
        getSavedCodeArguments(details),
      );
      form.setValue("pythonCodeDetails.arguments", {
        ...codeArguments,
        ...Object.fromEntries(unknownArguments.map((name) => [name, ""])),
      });
      form.setValue("pythonCodeDetails.parsingArgumentsError", parsingError);
      return;
    }

    const llmJudgeDetails = form.getValues("llmJudgeDetails");

    const [mainScore, ...additionalScores] = llmJudgeDetails.schema;
    if (mainScore) {
      const nextMainScore = {
        ...mainScore,
        name: mainScore.name.trim() || values.ruleName.trim(),
        description:
          rule || values.uiType === UI_EVALUATORS_RULE_TYPE.decision_model
            ? mainScore.description
            : "",
      };
      if (!isEqual(nextMainScore, mainScore)) {
        form.setValue("llmJudgeDetails.schema", [
          nextMainScore,
          ...additionalScores,
        ]);
      }
    }

    const { variables, parsingVariablesError } = getPromptVariables(
      llmJudgeDetails.messages,
      llmJudgeDetails.variables,
      values.scope,
    );
    form.setValue("llmJudgeDetails.variables", variables);
    form.setValue(
      "llmJudgeDetails.parsingVariablesError",
      parsingVariablesError,
    );
  }, [form, rule]);

  useEffect(() => {
    const subscription = form.watch((_, { name }) => {
      if (!form.formState.isSubmitted || !name || isDerivedField(name)) return;
      prepareForSubmit();
      form.trigger();
    });
    return () => subscription.unsubscribe();
  }, [form, prepareForSubmit]);

  const buildRule = useCallback((): EvaluatorsRule => {
    const values = form.getValues();
    const rule = {
      name: values.ruleName,
      project_ids: values.projectIds,
      sampling_rate: values.samplingRate,
      enabled: values.enabled,
      trigger_scope: values.triggerScope,
      filters: buildRuleFilters(values.filters),
      type: values.type,
    };

    if (values.uiType === UI_EVALUATORS_RULE_TYPE.python_code) {
      const details = form.getValues("pythonCodeDetails");
      return {
        ...rule,
        code:
          values.scope === EVALUATORS_RULE_SCOPE.thread
            ? { metric: details.metric }
            : {
                metric: details.metric,
                arguments: getSavedCodeArguments(details),
              },
      } as EvaluatorsRule;
    }

    const llmJudgeDetails = form.getValues("llmJudgeDetails");
    const code = convertLLMJudgeDataToLLMJudgeObject(
      {
        ...llmJudgeDetails,
        ...nameInlineVariables(
          llmJudgeDetails.messages,
          llmJudgeDetails.variables,
          savedVariables,
        ),
      },
      openAiPipelineMode,
    );
    return {
      ...rule,
      code:
        values.type === EVALUATORS_RULE_TYPE.thread_llm_judge
          ? { ...code, variables: undefined }
          : code,
    } as EvaluatorsRule;
  }, [form, savedVariables, openAiPipelineMode]);

  const convertCodeSignature = useCallback(() => {
    const details = form.getValues("pythonCodeDetails");
    const converted = convertToStandardSignature(
      details.metric,
      form.getValues("scope"),
      getSavedCodeArguments(details),
    );
    if (!converted) return false;
    form.setValue(
      "pythonCodeDetails",
      { metric: converted, arguments: {} },
      { shouldDirty: true },
    );
    form.clearErrors("pythonCodeDetails");
    return true;
  }, [form]);

  return {
    form,
    changeScope,
    applyTemplate,
    prepareForSubmit,
    buildRule,
    convertCodeSignature,
    promptMergeInfo,
  };
};

export default useRuleEditorForm;

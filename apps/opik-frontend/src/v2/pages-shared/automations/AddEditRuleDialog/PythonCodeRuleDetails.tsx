import React from "react";
import { UseFormReturn } from "react-hook-form";
import CodeMirror from "@uiw/react-codemirror";
import { pythonLanguage } from "@codemirror/lang-python";
import { EditorView } from "@codemirror/view";
import { Code } from "lucide-react";
import get from "lodash/get";

import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/AddEditRuleDialog/schema";
import LLMPromptMessagesVariables from "@/v2/pages-shared/llm/LLMPromptMessagesVariables/LLMPromptMessagesVariables";
import { FormControl, FormField, FormItem, FormMessage } from "@/ui/form";
import { Tag } from "@/ui/tag";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { parsePythonMethodParameters } from "@/lib/pythonArgumentsParser";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { resolveTraceEvaluatorVariableDefault } from "@/lib/llm";
import { reservedPythonMetricVariablesForScope } from "./helpers";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";

type PythonCodeRuleDetailsProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
  projectId: string;
  datasetColumnNames?: string[];
};

const PythonCodeRuleDetails: React.FC<PythonCodeRuleDetailsProps> = ({
  form,
  projectId,
  datasetColumnNames,
}) => {
  const theme = useCodemirrorTheme({
    editable: true,
  });

  const scope = form.watch("scope");
  const isThreadScope = scope === EVALUATORS_RULE_SCOPE.thread;
  const isSpanScope = scope === EVALUATORS_RULE_SCOPE.span;

  // Determine the type for autocomplete based on scope
  const autocompleteType = isSpanScope
    ? TRACE_DATA_TYPE.spans
    : TRACE_DATA_TYPE.traces;

  // Scope-appropriate reserved set, as LLMJudgeRuleDetails does — `spans` is a
  // trace-scope sentinel that span scope's schema rejects.
  const reservedVariables = reservedPythonMetricVariablesForScope(scope);

  return (
    <div className="flex flex-col gap-2">
      <FormField
        control={form.control}
        name="pythonCodeDetails.metric"
        render={({ field }) => {
          return (
            <FormItem>
              <FormControl>
                <div
                  className="overflow-hidden rounded-md border border-border"
                  data-testid="python-code-card"
                >
                  <div className="comet-body-s-accented flex h-10 items-center gap-2 border-b border-border bg-soft-background px-2">
                    <Tag variant="yellow" size="sm" className="px-1">
                      <Code className="size-3" />
                    </Tag>
                    Python code
                  </div>
                  <CodeMirror
                    theme={theme}
                    value={field.value}
                    minHeight="420px"
                    onChange={(value) => {
                      field.onChange(value);

                      // recalculate arguments (only for trace and span scope, not thread)
                      if (!isThreadScope) {
                        const currentArguments = form.getValues(
                          "pythonCodeDetails.arguments",
                        );
                        const localArguments: Record<string, string> = {};
                        let parsingArgumentsError: boolean = false;
                        try {
                          parsePythonMethodParameters(value, "score")
                            .map((v) => v.name)
                            .forEach((v: string) => {
                              localArguments[v] =
                                resolveTraceEvaluatorVariableDefault(
                                  v,
                                  currentArguments[v],
                                  scope,
                                  reservedVariables,
                                );
                            });
                        } catch (e) {
                          parsingArgumentsError = true;
                        }

                        form.setValue(
                          "pythonCodeDetails.arguments",
                          localArguments,
                        );
                        form.setValue(
                          "pythonCodeDetails.parsingArgumentsError",
                          parsingArgumentsError,
                        );
                      }
                    }}
                    extensions={[pythonLanguage, EditorView.lineWrapping]}
                  />
                </div>
              </FormControl>
              <FormMessage />
            </FormItem>
          );
        }}
      />
      {!isThreadScope && (
        <FormField
          control={form.control}
          name="pythonCodeDetails.arguments"
          render={({ field, formState }) => {
            const parsingArgumentsError = form.getValues(
              "pythonCodeDetails.parsingArgumentsError",
            );
            const validationErrors = get(formState.errors, [
              "pythonCodeDetails",
              "arguments",
            ]);

            return (
              <LLMPromptMessagesVariables
                parsingError={parsingArgumentsError}
                validationErrors={validationErrors}
                projectId={projectId}
                variables={field.value}
                onChange={field.onChange}
                description="All variables are automatically added based on the code snippet. They are extracted from the `score` method and are required."
                errorText="Code parsing error. The variables cannot be extracted."
                datasetColumnNames={datasetColumnNames}
                type={autocompleteType}
                includeIntermediateNodes
                reservedSentinels={reservedVariables}
              />
            );
          }}
        />
      )}
    </div>
  );
};

export default PythonCodeRuleDetails;

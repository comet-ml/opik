import React from "react";
import { UseFormReturn } from "react-hook-form";
import CodeMirror from "@uiw/react-codemirror";
import { pythonLanguage } from "@codemirror/lang-python";
import { EditorView } from "@codemirror/view";
import get from "lodash/get";

import { EvaluationRuleFormType } from "@/components/pages-shared/automations/AddEditRuleDialog/schema";
import LLMPromptMessagesVariables from "@/components/pages-shared/llm/LLMPromptMessagesVariables/LLMPromptMessagesVariables";
import {
  FormControl,
  FormField,
  FormItem,
  FormMessage,
} from "@/components/ui/form";
import { Label } from "@/components/ui/label";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { parsePythonMethodParameters } from "@/lib/pythonArgumentsParser";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";

type PythonCodeRuleDetailsProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
  projectName?: string;
  datasetColumnNames?: string[];
};

const PythonCodeRuleDetails: React.FC<PythonCodeRuleDetailsProps> = ({
  form,
  projectName,
  datasetColumnNames,
}) => {
  const theme = useCodemirrorTheme({
    editable: true,
  });

  const isThreadScope = form.watch("scope") === EVALUATORS_RULE_SCOPE.thread;

  return (
    <>
      <FormField
        control={form.control}
        name="pythonCodeDetails.metric"
        render={({ field }) => {
          return (
            <FormItem>
              <Label>Python code</Label>
              <FormControl>
                <div className="rounded-md">
                  <CodeMirror
                    theme={theme}
                    value={field.value}
                    onChange={(value) => {
                      field.onChange(value);

                      // recalculate arguments
                      const currentArguments = form.getValues(
                        "pythonCodeDetails.arguments",
                      );
                      const localArguments: Record<string, string> = {};
                      let parsingArgumentsError: boolean = false;
                      try {
                        parsePythonMethodParameters(value, "score")
                          .map((v) => v.name)
                          .forEach(
                            (v: string) =>
                              (localArguments[v] = currentArguments[v] ?? ""),
                          );
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
                projectId={form.watch("projectId")}
                variables={field.value}
                onChange={field.onChange}
                description="All variables are automatically added based on the code snippet. They are extracted from the `score` method and are required."
                errorText="Code parsing error. The variables cannot be extracted."
                projectName={projectName}
                datasetColumnNames={datasetColumnNames}
              />
            );
          }}
        />
      )}
    </>
  );
};

export default PythonCodeRuleDetails;

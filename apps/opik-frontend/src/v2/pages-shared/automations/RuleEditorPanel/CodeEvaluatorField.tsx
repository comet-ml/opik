import React, { useState } from "react";
import { UseFormReturn } from "react-hook-form";
import CodeMirror from "@uiw/react-codemirror";
import { pythonLanguage } from "@codemirror/lang-python";
import { EditorView } from "@codemirror/view";

import {
  FormControl,
  FormErrorSkeleton,
  FormField,
  FormItem,
  FormMessage,
} from "@/ui/form";
import { Description } from "@/ui/description";
import { Button } from "@/ui/button";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import {
  getCodeArgumentNames,
  getRecordErrorKeys,
  getSavedCodeArguments,
} from "@/v2/pages-shared/automations/RuleEditorPanel/helpers";
import { getLegacyCodeArguments } from "@/v2/pages-shared/automations/RuleEditorPanel/codeSignature";
import RuleEditorNotice from "@/v2/pages-shared/automations/RuleEditorPanel/RuleEditorNotice";
import RuleEditorCard from "@/v2/pages-shared/automations/RuleEditorPanel/RuleEditorCard";
import { SCOPE_ENTITY_LABEL } from "@/v2/pages-shared/automations/RuleEditorPanel/constants";

type CodeEvaluatorFieldProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
  onConvertSignature: () => boolean;
};

const formatNames = (names: string[]) =>
  names.length > 1
    ? `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}`
    : names.join("");

const CodeEvaluatorField: React.FC<CodeEvaluatorFieldProps> = ({
  form,
  onConvertSignature,
}) => {
  const theme = useCodemirrorTheme({ editable: true });
  const scope = form.watch("scope");
  const argumentNames = getCodeArgumentNames(scope);
  const entityLabel = SCOPE_ENTITY_LABEL[scope].replace(/s$/, "");

  const unknownArguments = getRecordErrorKeys(form.formState.errors, [
    "pythonCodeDetails",
    "arguments",
  ]);

  const legacyArguments = getLegacyCodeArguments(
    scope,
    getSavedCodeArguments(form.watch("pythonCodeDetails")),
  );
  const [conversionFailed, setConversionFailed] = useState(false);

  const hint =
    scope === EVALUATORS_RULE_SCOPE.thread
      ? "The score method gets the conversation of each thread."
      : `The score method can take ${formatNames(
          argumentNames,
        )} as parameters. They are filled from each ${entityLabel} when the evaluator runs.`;

  return (
    <FormField
      control={form.control}
      name="pythonCodeDetails.metric"
      render={({ field, fieldState }) => (
        <FormItem className="flex flex-1 flex-col gap-1">
          {legacyArguments.length > 0 && (
            <RuleEditorNotice>
              <span>
                {`This evaluator maps its parameters to ${entityLabel} fields: ${legacyArguments
                  .map(([name, path]) => `${name} → ${path}`)
                  .join(
                    ", ",
                  )}. New evaluators use the standard parameters instead.`}
              </span>
              {conversionFailed ? (
                <span className="text-destructive">
                  The code could not be converted automatically. Update the
                  score method by hand.
                </span>
              ) : (
                <Button
                  type="button"
                  variant="outline"
                  size="2xs"
                  className="self-start"
                  onClick={() => setConversionFailed(!onConvertSignature())}
                >
                  Convert to standard parameters
                </Button>
              )}
            </RuleEditorNotice>
          )}
          <RuleEditorCard
            hasError={Boolean(fieldState.error) || unknownArguments.length > 0}
            header={
              <span className="comet-body-xs-accented text-muted-slate">
                Python code
              </span>
            }
          >
            <FormControl>
              <CodeMirror
                theme={theme}
                value={field.value}
                onChange={field.onChange}
                onBlur={field.onBlur}
                className="flex min-h-80 flex-1 flex-col [&_.cm-editor]:flex-1"
                extensions={[pythonLanguage, EditorView.lineWrapping]}
              />
            </FormControl>
          </RuleEditorCard>
          <Description className="px-0.5">{hint}</Description>
          <FormMessage />
          {unknownArguments.map((name) => (
            <FormErrorSkeleton key={name}>
              {`Unknown parameter "${name}". The score method can take ${formatNames(
                argumentNames,
              )}.`}
            </FormErrorSkeleton>
          ))}
        </FormItem>
      )}
    />
  );
};

export default CodeEvaluatorField;

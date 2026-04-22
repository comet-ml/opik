import React, { useCallback, useState } from "react";
import { Controller, useFieldArray, useFormContext } from "react-hook-form";
import TextareaAutosize from "react-textarea-autosize";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { jsonLanguage } from "@codemirror/lang-json";

import { Separator } from "@/ui/separator";
import { TEXT_AREA_CLASSES } from "@/ui/textarea";
import { cn } from "@/lib/utils";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { Description } from "@/ui/description";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { ExecutionPolicy } from "@/types/test-suites";
import EvaluationCriteriaSection from "@/shared/EvaluationCriteriaSection/EvaluationCriteriaSection";
import { TestSuiteItemFormValues } from "./testSuiteItemFormSchema";

const EDITOR_EXTENSIONS = [jsonLanguage, EditorView.lineWrapping];
const EDITOR_BASIC_SETUP = {
  lineNumbers: false,
  foldGutter: false,
  highlightActiveLine: false,
  highlightSelectionMatches: false,
} as const;

interface TestSuiteItemFormProps {
  suiteAssertions: string[];
  suitePolicy: ExecutionPolicy;
  onOpenSettings: () => void;
}

const DescriptionSection: React.FC = () => {
  const { register } = useFormContext<TestSuiteItemFormValues>();

  return (
    <div>
      <h3 className="comet-body-s-accented mb-2">Description</h3>
      <TextareaAutosize
        {...register("description")}
        placeholder="Describe this item..."
        className={cn(TEXT_AREA_CLASSES, "min-h-0 resize-none")}
        minRows={1}
      />
    </div>
  );
};

const DataSection: React.FC = () => {
  const theme = useCodemirrorTheme({ editable: true });
  const { control } = useFormContext<TestSuiteItemFormValues>();
  const [jsonError, setJsonError] = useState<string | null>(null);

  function validateJson(value: string): void {
    try {
      const parsed = JSON.parse(value);
      if (typeof parsed === "object" && parsed !== null) {
        setJsonError(null);
      } else {
        setJsonError("Must be a JSON object");
      }
    } catch {
      setJsonError("Invalid JSON");
    }
  }

  return (
    <div>
      <h3 className="comet-body-s-accented mb-2">Data</h3>
      <Controller
        control={control}
        name="data"
        render={({ field }) => (
          <>
            <div
              className={cn(
                "overflow-hidden rounded-md border",
                jsonError && "border-destructive",
              )}
            >
              <CodeMirror
                theme={theme}
                value={field.value}
                onChange={(value) => {
                  field.onChange(value);
                  validateJson(value);
                }}
                extensions={EDITOR_EXTENSIONS}
                basicSetup={EDITOR_BASIC_SETUP}
              />
            </div>
            {jsonError && (
              <p className="comet-body-xs mt-1 text-destructive">{jsonError}</p>
            )}
            <Description className="comet-body-xs">
              {
                EXPLAINERS_MAP[
                  EXPLAINER_ID.what_format_is_this_to_add_my_test_suite_item
                ].description
              }
            </Description>
          </>
        )}
      />
    </div>
  );
};

interface FormEvaluationCriteriaSectionProps {
  suiteAssertions: string[];
  suitePolicy: ExecutionPolicy;
  onOpenSettings: () => void;
}

const FormEvaluationCriteriaSection: React.FC<
  FormEvaluationCriteriaSectionProps
> = ({ suiteAssertions, suitePolicy, onOpenSettings }) => {
  const form = useFormContext<TestSuiteItemFormValues>();
  const { fields, prepend, remove, update } = useFieldArray({
    control: form.control,
    name: "assertions",
  });

  const rawRunsPerItem = form.watch("runsPerItem");
  const rawPassThreshold = form.watch("passThreshold");
  const useGlobalPolicy = form.watch("useGlobalPolicy");

  const runsPerItem = useGlobalPolicy
    ? suitePolicy.runs_per_item
    : rawRunsPerItem;
  const passThreshold = useGlobalPolicy
    ? suitePolicy.pass_threshold
    : rawPassThreshold;

  const handleRunsChange = useCallback(
    (v: number) => {
      form.setValue("useGlobalPolicy", false);
      form.setValue("runsPerItem", v, { shouldDirty: true });
      const currentThreshold = form.getValues("passThreshold");
      if (currentThreshold > v) {
        form.setValue("passThreshold", v, { shouldDirty: true });
      }
    },
    [form],
  );

  const handleThresholdChange = useCallback(
    (v: number) => {
      form.setValue("useGlobalPolicy", false);
      form.setValue("passThreshold", v, { shouldDirty: true });
    },
    [form],
  );

  const handleRevertPolicy = useCallback(() => {
    form.setValue("useGlobalPolicy", true, { shouldDirty: true });
    form.setValue("runsPerItem", suitePolicy.runs_per_item);
    form.setValue("passThreshold", suitePolicy.pass_threshold);
  }, [form, suitePolicy]);

  return (
    <EvaluationCriteriaSection
      suiteAssertions={suiteAssertions}
      editableAssertions={fields.map((f) => f.value)}
      onChangeAssertion={(index, value) => update(index, { value })}
      onRemoveAssertion={(index) => remove(index)}
      onAddAssertion={() => prepend({ value: "" })}
      runsPerItem={runsPerItem}
      passThreshold={passThreshold}
      onRunsPerItemChange={handleRunsChange}
      onPassThresholdChange={handleThresholdChange}
      useGlobalPolicy={useGlobalPolicy}
      onRevertToDefaults={handleRevertPolicy}
      onOpenSettings={onOpenSettings}
      defaultRunsPerItem={suitePolicy.runs_per_item}
      defaultPassThreshold={suitePolicy.pass_threshold}
    />
  );
};

const TestSuiteItemForm: React.FC<TestSuiteItemFormProps> = ({
  suiteAssertions,
  suitePolicy,
  onOpenSettings,
}) => {
  return (
    <div className="flex flex-col gap-6 p-6 pt-4">
      <DescriptionSection />
      <DataSection />
      <Separator />
      <FormEvaluationCriteriaSection
        suiteAssertions={suiteAssertions}
        suitePolicy={suitePolicy}
        onOpenSettings={onOpenSettings}
      />
    </div>
  );
};

export default TestSuiteItemForm;

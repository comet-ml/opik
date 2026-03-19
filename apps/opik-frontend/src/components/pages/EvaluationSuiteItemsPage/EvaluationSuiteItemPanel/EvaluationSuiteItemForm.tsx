import React, { useState } from "react";
import { Controller, useFieldArray, useFormContext } from "react-hook-form";
import TextareaAutosize from "react-textarea-autosize";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { jsonLanguage } from "@codemirror/lang-json";
import { Settings2 } from "lucide-react";

import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Separator } from "@/components/ui/separator";
import { TEXT_AREA_CLASSES } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { useClampedIntegerInput } from "@/hooks/useClampedIntegerInput";
import AssertionsField from "@/components/shared/AssertionField/AssertionsField";
import { Description } from "@/components/ui/description";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { ExecutionPolicy, MAX_RUNS_PER_ITEM } from "@/types/evaluation-suites";
import { EvaluationSuiteItemFormValues } from "./evaluationSuiteItemFormSchema";

const EDITOR_EXTENSIONS = [jsonLanguage, EditorView.lineWrapping];
const EDITOR_BASIC_SETUP = {
  lineNumbers: false,
  foldGutter: false,
  highlightActiveLine: false,
  highlightSelectionMatches: false,
} as const;

interface EvaluationSuiteItemFormProps {
  suiteAssertions: string[];
  suitePolicy: ExecutionPolicy;
  onOpenSettings: () => void;
  showDataDescription?: boolean;
}

const DescriptionSection: React.FC = () => {
  const { register } = useFormContext<EvaluationSuiteItemFormValues>();

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

const DataSection: React.FC<{ showDescription?: boolean }> = ({
  showDescription,
}) => {
  const theme = useCodemirrorTheme({ editable: true });
  const { control } = useFormContext<EvaluationSuiteItemFormValues>();
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
            {showDescription && (
              <Description>
                {
                  EXPLAINERS_MAP[
                    EXPLAINER_ID
                      .what_format_is_this_to_add_my_evaluation_suite_item
                  ].description
                }
              </Description>
            )}
          </>
        )}
      />
    </div>
  );
};

interface EvaluationCriteriaSectionProps {
  suiteAssertions: string[];
  suitePolicy: ExecutionPolicy;
  onOpenSettings: () => void;
}

const EvaluationCriteriaSection: React.FC<EvaluationCriteriaSectionProps> = ({
  suiteAssertions,
  suitePolicy,
  onOpenSettings,
}) => {
  const form = useFormContext<EvaluationSuiteItemFormValues>();
  const { fields, append, remove, update } = useFieldArray({
    control: form.control,
    name: "assertions",
  });

  const runsPerItem = form.watch("runsPerItem");

  const runsInput = useClampedIntegerInput({
    value: runsPerItem,
    min: 1,
    max: MAX_RUNS_PER_ITEM,
    onCommit: (v) => {
      form.setValue("runsPerItem", v, { shouldDirty: true });
      const currentThreshold = form.getValues("passThreshold");
      if (currentThreshold > v) {
        form.setValue("passThreshold", v, { shouldDirty: true });
      }
    },
  });

  const thresholdInput = useClampedIntegerInput({
    value: form.watch("passThreshold"),
    min: 1,
    max: runsPerItem,
    onCommit: (v) => form.setValue("passThreshold", v, { shouldDirty: true }),
  });

  return (
    <div>
      <h3 className="comet-body-accented mb-1">Evaluation criteria</h3>
      <p className="comet-body-s mb-4 text-light-slate">
        Define the conditions required for the evaluation to pass.
      </p>

      <div className="mb-4 flex gap-4">
        <div className="flex flex-1 flex-col gap-1">
          <Label>Runs for this item</Label>
          <Input
            dimension="sm"
            className={cn("[&::-webkit-inner-spin-button]:appearance-none", {
              "border-destructive": runsInput.isInvalid,
            })}
            type="number"
            min={1}
            max={MAX_RUNS_PER_ITEM}
            value={runsInput.displayValue}
            onChange={runsInput.onChange}
            onFocus={runsInput.onFocus}
            onBlur={runsInput.onBlur}
            onKeyDown={runsInput.onKeyDown}
          />
          <span className="comet-body-xs text-light-slate">
            Global default is {suitePolicy.runs_per_item}
          </span>
        </div>
        <div className="flex flex-1 flex-col gap-1">
          <Label>Pass threshold</Label>
          <Input
            dimension="sm"
            className={cn("[&::-webkit-inner-spin-button]:appearance-none", {
              "border-destructive": thresholdInput.isInvalid,
            })}
            type="number"
            min={1}
            max={runsPerItem}
            value={thresholdInput.displayValue}
            onChange={thresholdInput.onChange}
            onFocus={thresholdInput.onFocus}
            onBlur={thresholdInput.onBlur}
            onKeyDown={thresholdInput.onKeyDown}
          />
          <span className="comet-body-xs text-light-slate">
            Global default is {suitePolicy.pass_threshold}
          </span>
        </div>
      </div>

      <div className="flex flex-col gap-1">
        <span className="comet-body-s-accented">Assertions</span>
        <div className="flex items-center justify-between">
          <span className="comet-body-s text-light-slate">
            Define the conditions for this evaluation to pass
          </span>
          <button
            type="button"
            className="comet-body-s inline-flex shrink-0 items-center gap-1 border-b border-foreground text-foreground"
            onClick={onOpenSettings}
          >
            <Settings2 className="size-3.5 shrink-0" />
            Manage global assertions
          </button>
        </div>

        <AssertionsField
          readOnlyAssertions={suiteAssertions}
          editableAssertions={fields.map((f) => f.value)}
          onChangeEditable={(index, value) => update(index, { value })}
          onRemoveEditable={(index) => remove(index)}
          onAdd={() => append({ value: "" })}
        />
      </div>
    </div>
  );
};

const EvaluationSuiteItemForm: React.FC<EvaluationSuiteItemFormProps> = ({
  suiteAssertions,
  suitePolicy,
  onOpenSettings,
  showDataDescription,
}) => {
  return (
    <div className="flex flex-col gap-6 p-6 pt-4">
      <DescriptionSection />
      <DataSection showDescription={showDataDescription} />
      <Separator />
      <EvaluationCriteriaSection
        suiteAssertions={suiteAssertions}
        suitePolicy={suitePolicy}
        onOpenSettings={onOpenSettings}
      />
    </div>
  );
};

export default EvaluationSuiteItemForm;

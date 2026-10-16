import React from "react";
import { UseFormReturn, useWatch } from "react-hook-form";
import isEqual from "lodash/isEqual";
import { Loader2 } from "lucide-react";

import { Button } from "@/ui/button";
import { Switch } from "@/ui/switch";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";

const ENABLED_SWITCH_ID = "rule-editor-enabled";

type RuleEditorFooterProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
  isEdit: boolean;
  onCancel: () => void;
  onSubmit: () => void;
  isSubmitting: boolean;
  hasPendingChanges?: boolean;
};

const RuleEditorFooter: React.FC<RuleEditorFooterProps> = ({
  form,
  isEdit,
  onCancel,
  onSubmit,
  isSubmitting,
  hasPendingChanges = false,
}) => {
  const values = useWatch({ control: form.control });
  const hasChanges =
    hasPendingChanges || !isEqual(values, form.formState.defaultValues);

  return (
    <div className="flex shrink-0 items-center gap-2 border-t border-border px-5 py-3">
      <div className="flex flex-1 items-center">
        <Button variant="outline" size="sm" className="gap-1.5" asChild>
          <label htmlFor={ENABLED_SWITCH_ID} className="cursor-pointer">
            <Switch
              id={ENABLED_SWITCH_ID}
              size="2xs"
              checked={Boolean(values.enabled)}
              onCheckedChange={(enabled) => form.setValue("enabled", enabled)}
            />
            Enabled
          </label>
        </Button>
      </div>
      <Button variant="outline" size="sm" onClick={onCancel}>
        Cancel
      </Button>
      <Button
        size="sm"
        onClick={onSubmit}
        disabled={isSubmitting || (isEdit && !hasChanges)}
      >
        {isSubmitting && <Loader2 className="mr-1.5 size-3.5 animate-spin" />}
        {isEdit ? "Save changes" : "Create evaluator"}
      </Button>
    </div>
  );
};

export default RuleEditorFooter;

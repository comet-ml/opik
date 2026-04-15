import React, { useCallback, useMemo } from "react";
import { useFieldArray, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
  DialogClose,
  DialogAutoScrollBody,
} from "@/ui/dialog";
import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import { cn } from "@/lib/utils";
import { Label } from "@/ui/label";
import AssertionsField from "@/shared/AssertionField/AssertionsField";
import { Form } from "@/ui/form";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import { useDatasetEntityIdFromURL } from "@/v2/hooks/useDatasetEntityIdFromURL";
import { useClampedIntegerInput } from "@/hooks/useClampedIntegerInput";
import {
  useSuiteAssertions,
  useDraftExecutionPolicy,
  useDraftAssertionActions,
} from "@/store/TestSuiteDraftStore";
import {
  DEFAULT_EXECUTION_POLICY,
  MAX_RUNS_PER_ITEM,
} from "@/types/test-suites";
import {
  PASS_CRITERIA_TITLE,
  PASS_CRITERIA_DESCRIPTION,
} from "@/constants/test-suites";
import { extractAssertions } from "@/lib/assertion-converters";

const settingsSchema = z.object({
  runsPerItem: z.number().min(1).max(MAX_RUNS_PER_ITEM),
  passThreshold: z.number().min(1),
  assertions: z.array(z.object({ value: z.string() })),
});

type SettingsFormType = z.infer<typeof settingsSchema>;

interface EditTestSuiteSettingsDialogProps {
  open: boolean;
  setOpen: (open: boolean) => void;
}

interface SettingsFormProps {
  defaultValues: SettingsFormType;
  onSubmit: (data: SettingsFormType) => void;
}

const SettingsForm: React.FC<SettingsFormProps> = ({
  defaultValues,
  onSubmit,
}) => {
  const form = useForm<SettingsFormType>({
    resolver: zodResolver(settingsSchema),
    defaultValues,
    mode: "onChange",
  });

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
      form.setValue("runsPerItem", v);
      const currentThreshold = form.getValues("passThreshold");
      if (currentThreshold > v) {
        form.setValue("passThreshold", v);
      }
    },
  });

  const thresholdInput = useClampedIntegerInput({
    value: form.watch("passThreshold"),
    min: 1,
    max: runsPerItem,
    onCommit: (v) => form.setValue("passThreshold", v),
  });

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} className="grid gap-4">
        <DialogHeader>
          <DialogTitle>Test settings</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <div className="flex flex-col">
            <div className="mb-4 flex flex-col gap-1">
              <div className="mb-1">
                <Label className="comet-body-s-accented">
                  Global assertions
                </Label>
                <p className="comet-body-xs text-light-slate">
                  Define the global conditions all items in this test suite must
                  pass.
                </p>
              </div>
              <div className="pt-1.5">
                <AssertionsField
                  editableAssertions={fields.map((f) => f.value)}
                  onChangeEditable={(index, value) => update(index, { value })}
                  onRemoveEditable={(index) => remove(index)}
                  onAdd={() => append({ value: "" })}
                  placeholder="e.g. Response should be factually accurate and cite sources"
                />
              </div>
            </div>

            <div className="mb-4">
              <h3 className="comet-body-s-accented">{PASS_CRITERIA_TITLE}</h3>
              <p className="comet-body-xs text-light-slate">
                {PASS_CRITERIA_DESCRIPTION}
              </p>
            </div>

            <div className="mb-4 flex gap-4">
              <div className="flex flex-1 flex-col gap-1">
                <Label
                  htmlFor="runs-per-item"
                  className="comet-body-xs-accented"
                >
                  Default runs per item
                </Label>
                <Input
                  id="runs-per-item"
                  dimension="sm"
                  className={cn({
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
              </div>
              <div className="flex flex-1 flex-col gap-1">
                <Label
                  htmlFor="pass-threshold"
                  className="comet-body-xs-accented"
                >
                  Default pass threshold
                </Label>
                <Input
                  id="pass-threshold"
                  dimension="sm"
                  className={cn({
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
              </div>
            </div>
          </div>
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button type="submit">Save</Button>
        </DialogFooter>
      </form>
    </Form>
  );
};

export const EditTestSuiteSettingsDialog: React.FC<
  EditTestSuiteSettingsDialogProps
> = ({ open, setOpen }) => {
  const suiteId = useDatasetEntityIdFromURL();

  const { data: versionsData } = useDatasetVersionsList({
    datasetId: suiteId,
    page: 1,
    size: 1,
  });
  const draftAssertions = useSuiteAssertions();
  const draftExecutionPolicy = useDraftExecutionPolicy();
  const { updateSuiteAssertions, updateExecutionPolicy } =
    useDraftAssertionActions();

  const latestVersionData = versionsData?.content?.[0];
  const serverExecutionPolicy =
    latestVersionData?.execution_policy ?? DEFAULT_EXECUTION_POLICY;
  const evaluators = latestVersionData?.evaluators ?? [];

  const serverAssertions = extractAssertions(evaluators);

  // Use draft values when available, fall back to server values
  const effectiveAssertions = draftAssertions ?? serverAssertions;
  const effectiveExecutionPolicy =
    draftExecutionPolicy ?? serverExecutionPolicy;

  // Key-based reset: when dialog opens, React remounts SettingsForm
  const resetKey = open ? `open` : "closed";

  const defaultValues: SettingsFormType = useMemo(
    () => ({
      runsPerItem: effectiveExecutionPolicy.runs_per_item,
      passThreshold: effectiveExecutionPolicy.pass_threshold,
      assertions: effectiveAssertions.map((a) => ({ value: a })),
    }),
    [effectiveExecutionPolicy, effectiveAssertions],
  );

  const handleSubmit = useCallback(
    (data: SettingsFormType) => {
      updateSuiteAssertions(
        data.assertions.map((a) => a.value.trim()).filter(Boolean),
        serverAssertions,
      );
      updateExecutionPolicy(
        { runs_per_item: data.runsPerItem, pass_threshold: data.passThreshold },
        serverExecutionPolicy,
      );

      setOpen(false);
    },
    [
      serverAssertions,
      serverExecutionPolicy,
      updateSuiteAssertions,
      updateExecutionPolicy,
      setOpen,
    ],
  );

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <SettingsForm
          key={resetKey}
          defaultValues={defaultValues}
          onSubmit={handleSubmit}
        />
      </DialogContent>
    </Dialog>
  );
};

export default EditTestSuiteSettingsDialog;

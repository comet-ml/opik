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
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import TextareaAutosize from "react-textarea-autosize";
import { cn } from "@/lib/utils";
import { TEXT_AREA_CLASSES } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import AssertionsField from "@/components/shared/AssertionField/AssertionsField";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormMessage,
} from "@/components/ui/form";
import { Separator } from "@/components/ui/separator";
import useDatasetById from "@/api/datasets/useDatasetById";
import useDatasetUpdateMutation from "@/api/datasets/useDatasetUpdateMutation";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import { useSuiteIdFromURL } from "@/hooks/useSuiteIdFromURL";
import { useClampedIntegerInput } from "@/hooks/useClampedIntegerInput";
import {
  useSuiteAssertions,
  useDraftExecutionPolicy,
  useDraftAssertionActions,
} from "@/store/EvaluationSuiteDraftStore";
import {
  DEFAULT_EXECUTION_POLICY,
  MAX_RUNS_PER_ITEM,
} from "@/types/evaluation-suites";
import { extractAssertions } from "@/lib/assertion-converters";

const settingsSchema = z.object({
  name: z.string().min(1, "Name is required"),
  description: z.string(),
  runsPerItem: z.number().min(1).max(MAX_RUNS_PER_ITEM),
  passThreshold: z.number().min(1),
  assertions: z.array(z.object({ value: z.string() })),
});

type SettingsFormType = z.infer<typeof settingsSchema>;

interface EditEvaluationSuiteSettingsDialogProps {
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
          <DialogTitle>Edit evaluation suite</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <div className="flex flex-col">
            <FormField
              control={form.control}
              name="name"
              render={({ field }) => (
                <FormItem className="pb-4">
                  <Label>Name</Label>
                  <FormControl>
                    <Input
                      dimension="sm"
                      placeholder="Enter evaluation suite name"
                      {...field}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="description"
              render={({ field }) => (
                <FormItem className="pb-4">
                  <Label>Description</Label>
                  <FormControl>
                    <TextareaAutosize
                      placeholder="Dataset description"
                      className={cn(TEXT_AREA_CLASSES, "min-h-0 resize-none")}
                      minRows={2}
                      maxRows={6}
                      {...field}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <Separator className="mb-4" />

            <div className="mb-4">
              <h3 className="comet-body-accented">Evaluation criteria</h3>
              <p className="comet-body-s text-light-slate">
                Define the conditions required for the evaluation to pass
              </p>
            </div>

            <div className="mb-4 flex gap-4">
              <div className="flex flex-1 flex-col gap-1">
                <Label htmlFor="runs-per-item">Default runs per item</Label>
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
                <Label htmlFor="pass-threshold">Default pass threshold</Label>
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

            <div className="flex flex-col gap-1 pb-4">
              <div className="mb-1">
                <Label>Global assertions</Label>
                <p className="comet-body-s text-light-slate">
                  Define the global conditions all items in this evaluation
                  suite must pass.
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
          </div>
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button type="submit">Update evaluation suite</Button>
        </DialogFooter>
      </form>
    </Form>
  );
};

export const EditEvaluationSuiteSettingsDialog: React.FC<
  EditEvaluationSuiteSettingsDialogProps
> = ({ open, setOpen }) => {
  const suiteId = useSuiteIdFromURL();

  const { data: suite } = useDatasetById({ datasetId: suiteId });
  const { data: versionsData } = useDatasetVersionsList({
    datasetId: suiteId,
    page: 1,
    size: 1,
  });
  const { mutate: updateSuite } = useDatasetUpdateMutation();
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
      name: suite?.name ?? "",
      description: suite?.description ?? "",
      runsPerItem: effectiveExecutionPolicy.runs_per_item,
      passThreshold: effectiveExecutionPolicy.pass_threshold,
      assertions: effectiveAssertions.map((a) => ({ value: a })),
    }),
    [suite, effectiveExecutionPolicy, effectiveAssertions],
  );

  const handleSubmit = useCallback(
    (data: SettingsFormType) => {
      const nameChanged = data.name !== (suite?.name ?? "");
      const descriptionChanged =
        data.description !== (suite?.description ?? "");

      if (nameChanged || descriptionChanged) {
        updateSuite({
          dataset: {
            ...suite,
            id: suiteId,
            name: data.name,
            description: data.description,
          },
        });
      }

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
      suite,
      suiteId,
      serverAssertions,
      serverExecutionPolicy,
      updateSuite,
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

export default EditEvaluationSuiteSettingsDialog;

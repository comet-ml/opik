import React, { useCallback, useEffect, useMemo } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Button } from "@/ui/button";
import { useToast } from "@/ui/use-toast";
import { Loader2 } from "lucide-react";
import { Span, Trace } from "@/types/traces";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import useFeedbackScoresBatchMutation from "@/api/traces/useFeedbackScoresBatchMutation";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useAppStore from "@/store/AppStore";
import { FeedbackDefinition } from "@/types/feedback-definitions";
import SelectBox from "@/shared/SelectBox/SelectBox";
import { SelectItem } from "@/ui/select";
import { DropdownOption } from "@/types/shared";
import { Textarea } from "@/ui/textarea";
import FeedbackScoreValueInput, {
  FeedbackScoreValue,
} from "@/v2/pages-shared/traces/FeedbackScoreValueInput/FeedbackScoreValueInput";
import { validateFeedbackScoreDefinitionValue } from "@/lib/traces";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/ui/form";

const MAX_ANNOTATE_ROWS = 500;

/**
 * Fetch up to 1,000 feedback definitions in a single request to populate
 * the score selector without requiring paginated dropdown controls.
 */
export const FEEDBACK_DEFINITIONS_PAGE_SIZE = 1000;

export const createAnnotateTracesFormSchema = (
  feedbackDefinitions: FeedbackDefinition[],
) => {
  return z
    .object({
      name: z.string().min(1, "Score name is required"),
      value: z.union([z.number(), z.string()]),
      categoryName: z.string().optional(),
      reason: z.string().optional(),
    })
    .superRefine((data, ctx) => {
      if (!data.name) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ["name"],
          message: "Please select a score",
        });
        return;
      }

      const definition = feedbackDefinitions.find((d) => d.name === data.name);
      if (!definition) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ["name"],
          message: "Score definition not found",
        });
        return;
      }

      if (
        data.value === undefined ||
        data.value === null ||
        data.value === ""
      ) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ["value"],
          message: "Value is required",
        });
        return;
      }

      const numValue = Number(data.value);
      if (Number.isNaN(numValue)) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ["value"],
          message: "Value must be a number",
        });
        return;
      }

      const validationResult = validateFeedbackScoreDefinitionValue(
        definition,
        numValue,
        data.categoryName,
      );
      if (!validationResult.isValid && validationResult.errorMessage) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ["value"],
          message: validationResult.errorMessage,
        });
      }
    });
};

export type AnnotateTracesFormValues = z.infer<
  ReturnType<typeof createAnnotateTracesFormSchema>
>;

export const DEFAULT_ANNOTATE_TRACES_FORM_VALUES: AnnotateTracesFormValues = {
  name: "",
  value: "",
  categoryName: "",
  reason: "",
};

type AnnotateTracesDialogProps = {
  rows: Array<Trace | Span>;
  open: boolean | number;
  setOpen: (open: boolean | number) => void;
  type: TRACE_DATA_TYPE;
  projectName?: string;
};

const AnnotateTracesDialog: React.FunctionComponent<
  AnnotateTracesDialogProps
> = ({ rows, open, setOpen, type, projectName }) => {
  const { toast } = useToast();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const {
    data: feedbackDefinitionsData,
    isLoading: isDefinitionsLoading,
    isError: isDefinitionsError,
    refetch: refetchDefinitions,
  } = useFeedbackDefinitionsList(
    {
      workspaceName,
      page: 1,
      size: FEEDBACK_DEFINITIONS_PAGE_SIZE,
    },
    { enabled: Boolean(open) },
  );

  const { mutateAsync: scoreBatch } = useFeedbackScoresBatchMutation();

  const isSpanType = type === TRACE_DATA_TYPE.spans;
  const entityCopy = isSpanType ? "spans" : "traces";

  const feedbackDefinitions: FeedbackDefinition[] = useMemo(
    () => feedbackDefinitionsData?.content || [],
    [feedbackDefinitionsData?.content],
  );

  const scoreSelectOptions = useMemo(
    () =>
      feedbackDefinitions.map((definition) => ({
        label: definition.name,
        value: definition.name,
      })),
    [feedbackDefinitions],
  );

  const schema = useMemo(
    () => createAnnotateTracesFormSchema(feedbackDefinitions),
    [feedbackDefinitions],
  );

  const form = useForm<AnnotateTracesFormValues>({
    resolver: zodResolver(schema),
    mode: "onChange",
    defaultValues: DEFAULT_ANNOTATE_TRACES_FORM_VALUES,
  });

  const selectedScoreName = form.watch("name");
  const selectedDefinition = useMemo(
    () =>
      feedbackDefinitions.find(
        (definition) => definition.name === selectedScoreName,
      ),
    [feedbackDefinitions, selectedScoreName],
  );

  useEffect(() => {
    if (open && rows.length > MAX_ANNOTATE_ROWS) {
      toast({
        title: "Error",
        description: `You can only annotate up to ${MAX_ANNOTATE_ROWS} ${entityCopy} at a time. Please select fewer items.`,
        variant: "destructive",
      });
      setOpen(false);
    }
  }, [open, rows.length, entityCopy, setOpen, toast]);

  useEffect(() => {
    if (open) {
      form.reset(DEFAULT_ANNOTATE_TRACES_FORM_VALUES);
    }
  }, [open, form]);

  const handleScoreNameChange = (name: string) => {
    form.setValue("name", name, { shouldValidate: true });
    form.setValue("value", "", { shouldValidate: true });
    form.setValue("categoryName", "", { shouldValidate: true });
  };

  const handleScoreValueChange = useCallback(
    ({
      value: newValue,
      categoryName: newCategory,
      status,
    }: FeedbackScoreValue) => {
      form.setValue("categoryName", newCategory ?? "", {
        shouldValidate: true,
        shouldDirty: true,
      });
      form.setValue("value", newValue !== undefined ? newValue : "", {
        shouldValidate: true,
        shouldDirty: true,
      });
      if (status === "invalid") {
        form.setError("value", {
          type: "manual",
          message: "Value is out of range",
        });
      } else {
        form.clearErrors("value");
      }
    },
    [form],
  );

  const handleApply = async (formData: AnnotateTracesFormValues) => {
    try {
      await scoreBatch({
        scores: rows.map((row) => ({
          id: row.id,
          name: formData.name,
          categoryName: formData.categoryName || undefined,
          // Preserve the raw string/number value — do NOT wrap in Number()
          // to avoid IEEE-754 float rounding of high-precision BigDecimal strings.
          value: formData.value,
          reason: formData.reason || undefined,
          projectName: projectName,
        })),
        isSpanType,
      });

      toast({
        title: "Annotations applied",
        description: `Successfully annotated ${rows.length} ${entityCopy}.`,
      });
      form.reset();
      setOpen(false);
    } catch {
      // Handled by mutation onError toast
    }
  };

  const isSubmitting = form.formState.isSubmitting;
  const isFormValid = form.formState.isValid;

  return (
    <Dialog open={Boolean(open)} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg" data-testid="annotate-bulk-dialog">
        <DialogHeader>
          <DialogTitle>Annotate {entityCopy}</DialogTitle>
        </DialogHeader>
        <p className="comet-body-s text-light-slate">
          Apply the same feedback score to {rows.length} selected {entityCopy}.
        </p>
        <Form {...form}>
          <form
            onSubmit={form.handleSubmit(handleApply)}
            className="flex flex-col gap-3 py-2"
          >
            {isDefinitionsError ? (
              <div
                className="comet-body-s text-destructive"
                data-testid="annotate-bulk-error"
              >
                Failed to load feedback definitions.
                <Button
                  variant="link"
                  size="sm"
                  type="button"
                  onClick={() => refetchDefinitions()}
                >
                  Retry
                </Button>
              </div>
            ) : isDefinitionsLoading ? (
              <div className="comet-body-s text-light-slate">
                Loading feedback definitions…
              </div>
            ) : (
              <>
                <FormField
                  control={form.control}
                  name="name"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel className="comet-body-s-accented pb-1">
                        Score
                      </FormLabel>
                      <FormControl>
                        <SelectBox
                          value={field.value ?? ""}
                          options={scoreSelectOptions}
                          onChange={handleScoreNameChange}
                          className="h-8 min-w-[200px] py-1"
                          testId="annotate-bulk-score-select"
                          disabled={isSubmitting}
                          renderTrigger={(val) => {
                            if (!val) {
                              return (
                                <div className="truncate">Select a score</div>
                              );
                            }
                            return <span className="text-nowrap">{val}</span>;
                          }}
                          renderOption={(option: DropdownOption<string>) => (
                            <SelectItem key={option.value} value={option.value}>
                              {option.label}
                            </SelectItem>
                          )}
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />

                {selectedDefinition && (
                  <FormField
                    control={form.control}
                    name="value"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel className="comet-body-s-accented pb-1">
                          Value
                        </FormLabel>
                        <FormControl>
                          <FeedbackScoreValueInput
                            feedbackDefinition={selectedDefinition}
                            value={field.value ?? ""}
                            categoryName={form.watch("categoryName")}
                            disabled={isSubmitting}
                            onChange={handleScoreValueChange}
                            testIdPrefix="annotate-bulk"
                          />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                )}

                {selectedDefinition && (
                  <FormField
                    control={form.control}
                    name="reason"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel className="comet-body-s-accented pb-1">
                          Reason (optional)
                        </FormLabel>
                        <FormControl>
                          <Textarea
                            placeholder="Add a reason..."
                            value={field.value ?? ""}
                            onChange={field.onChange}
                            className="min-h-8 resize-none py-1"
                            data-testid="annotate-bulk-reason-input"
                            disabled={isSubmitting}
                          />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                )}
              </>
            )}

            <DialogFooter className="pt-2">
              <Button
                variant="outline"
                type="button"
                onClick={() => setOpen(false)}
                disabled={isSubmitting}
              >
                Cancel
              </Button>
              <Button
                type="submit"
                disabled={!isFormValid || isSubmitting}
                data-testid="annotate-bulk-apply-button"
              >
                {isSubmitting && (
                  <Loader2 className="mr-1 size-3 animate-spin" />
                )}
                Apply to {rows.length} {entityCopy}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
};

export default AnnotateTracesDialog;

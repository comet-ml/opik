import React, { useMemo, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useTraceFeedbackScoreSetMutation from "@/api/traces/useTraceFeedbackScoreSetMutation";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { parseNumericFeedbackScore } from "@/lib/traces";
import useAppStore from "@/store/AppStore";
import {
  FEEDBACK_DEFINITION_TYPE,
  FeedbackDefinition,
} from "@/types/feedback-definitions";
import { Span, Trace } from "@/types/traces";
import { Button } from "@/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/ui/form";
import { Input } from "@/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/ui/select";
import { Spinner } from "@/ui/spinner";
import { Textarea } from "@/ui/textarea";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import { useToast } from "@/ui/use-toast";

const BOOLEAN_TRUE_ID = "__boolean_true__";
const BOOLEAN_FALSE_ID = "__boolean_false__";
const BULK_ANNOTATE_CONCURRENCY = 5;

const DEFAULT_FORM_VALUES = {
  definitionId: "",
  value: "",
  categoryId: "",
  reason: "",
};

type AnnotateFormValues = typeof DEFAULT_FORM_VALUES;

type CategoryChoice = {
  id: string;
  label: string;
  value: number;
};

const createAnnotateFormSchema = (
  getDefinition: (id: string) => FeedbackDefinition | undefined,
) =>
  z
    .object({
      definitionId: z.string().min(1, "Select a feedback definition"),
      value: z.string(),
      categoryId: z.string(),
      reason: z.string().optional(),
    })
    .superRefine((values, ctx) => {
      const definition = getDefinition(values.definitionId);
      if (!definition) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ["definitionId"],
          message: "Select a feedback definition",
        });
        return;
      }

      if (definition.type === FEEDBACK_DEFINITION_TYPE.numerical) {
        if (
          parseNumericFeedbackScore(values.value, definition.details) ===
          undefined
        ) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            path: ["value"],
            message: "Enter a valid score",
          });
        }
        return;
      }

      if (!values.categoryId) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ["categoryId"],
          message: "Select a score",
        });
      }
    });

const mapWithConcurrency = async <T, R>(
  items: T[],
  concurrency: number,
  mapper: (item: T) => Promise<R>,
): Promise<PromiseSettledResult<R>[]> => {
  if (items.length === 0) {
    return [];
  }

  const results: PromiseSettledResult<R>[] = new Array(items.length);
  let nextIndex = 0;

  const worker = async () => {
    while (true) {
      const index = nextIndex++;
      if (index >= items.length) {
        return;
      }

      try {
        const value = await mapper(items[index]);
        results[index] = { status: "fulfilled", value };
      } catch (reason) {
        results[index] = { status: "rejected", reason };
      }
    }
  };

  await Promise.all(
    Array.from({ length: Math.min(concurrency, items.length) }, () => worker()),
  );

  return results;
};

type AnnotateTracesOrSpansDialogProps = {
  rows: Array<Trace | Span>;
  type: TRACE_DATA_TYPE;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const AnnotateTracesOrSpansDialog: React.FC<
  AnnotateTracesOrSpansDialogProps
> = ({ rows, type, open, setOpen }) => {
  const [definitionFilter, setDefinitionFilter] = useState("");
  const [isApplying, setIsApplying] = useState(false);
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data, isPending, isError } = useFeedbackDefinitionsList(
    { workspaceName, page: 1, size: 1000 },
    { enabled: open },
  );
  const { mutateAsync } = useTraceFeedbackScoreSetMutation();
  const { toast } = useToast();
  const definitions = data?.content ?? [];
  const definitionsRef = useRef(definitions);
  definitionsRef.current = definitions;

  const form = useForm<AnnotateFormValues>({
    resolver: zodResolver(
      createAnnotateFormSchema((id) =>
        definitionsRef.current.find((item) => item.id === id),
      ),
    ),
    mode: "onChange",
    defaultValues: DEFAULT_FORM_VALUES,
  });

  const definitionId = form.watch("definitionId");
  const { isValid } = form.formState;

  const definition = useMemo(
    () => definitions.find((item) => item.id === definitionId),
    [definitions, definitionId],
  );
  const categories = useMemo<CategoryChoice[]>(() => {
    if (definition?.type === FEEDBACK_DEFINITION_TYPE.categorical) {
      return Object.entries(definition.details.categories)
        .sort((a, b) => a[1] - b[1])
        .map(([name, categoryValue]) => ({
          id: name,
          label: name,
          value: categoryValue,
        }));
    }

    if (definition?.type === FEEDBACK_DEFINITION_TYPE.boolean) {
      return [
        {
          id: BOOLEAN_TRUE_ID,
          label: definition.details.true_label,
          value: 1,
        },
        {
          id: BOOLEAN_FALSE_ID,
          label: definition.details.false_label,
          value: 0,
        },
      ];
    }

    return [];
  }, [definition]);

  const filteredDefinitions = useMemo(() => {
    const query = definitionFilter.trim().toLowerCase();
    if (!query) {
      return definitions;
    }
    return definitions.filter((item) =>
      item.name.toLowerCase().includes(query),
    );
  }, [definitionFilter, definitions]);

  const resetFormState = () => {
    form.reset(DEFAULT_FORM_VALUES);
    setDefinitionFilter("");
  };

  const handleClose = () => {
    if (isApplying) return;
    resetFormState();
    setOpen(false);
  };

  const onSubmit = async (values: AnnotateFormValues) => {
    if (!definition || !rows.length || isApplying) return;

    const selectedCategory = categories.find(
      (category) => category.id === values.categoryId,
    );
    const score =
      definition.type === FEEDBACK_DEFINITION_TYPE.numerical
        ? parseNumericFeedbackScore(values.value, definition.details)
        : selectedCategory?.value;

    if (score === undefined || !Number.isFinite(score)) return;

    setIsApplying(true);
    try {
      // Wait for every request, including after a partial failure.
      const results = await mapWithConcurrency(
        rows,
        BULK_ANNOTATE_CONCURRENCY,
        (row) =>
          mutateAsync({
            name: definition.name,
            value: score,
            reason: values.reason || undefined,
            categoryName:
              definition.type === FEEDBACK_DEFINITION_TYPE.numerical
                ? undefined
                : selectedCategory?.label,
            traceId:
              type === TRACE_DATA_TYPE.spans ? (row as Span).trace_id : row.id,
            ...(type === TRACE_DATA_TYPE.spans && { spanId: row.id }),
          }),
      );
      if (results.every((result) => result.status === "fulfilled")) {
        toast({ title: "Annotations applied" });
        resetFormState();
        setOpen(false);
      }
    } finally {
      // The mutation hook displays request errors.
      setIsApplying(false);
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(nextOpen) => {
        if (!nextOpen) {
          handleClose();
        }
      }}
    >
      <DialogContent className="sm:max-w-[600px]">
        <DialogHeader>
          <DialogTitle>
            <span>
              {type === TRACE_DATA_TYPE.spans
                ? "Annotate spans"
                : "Annotate traces"}
            </span>
          </DialogTitle>
          <DialogDescription>
            <span>{rows.length} selected</span>
          </DialogDescription>
        </DialogHeader>
        <DialogAutoScrollBody>
          <Form {...form}>
            <form
              id="annotate-bulk-form"
              onSubmit={form.handleSubmit(onSubmit)}
            >
              <fieldset disabled={isApplying} className="space-y-4">
                <FormField
                  control={form.control}
                  name="definitionId"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Feedback definition</FormLabel>
                      <Input
                        value={definitionFilter}
                        onChange={(event) =>
                          setDefinitionFilter(event.target.value)
                        }
                        placeholder="Search definitions"
                        disabled={isApplying || isPending || isError}
                      />
                      <Select
                        value={field.value || undefined}
                        onValueChange={(id) => {
                          field.onChange(id);
                          form.setValue("value", "", {
                            shouldValidate: true,
                            shouldDirty: true,
                          });
                          form.setValue("categoryId", "", {
                            shouldValidate: true,
                            shouldDirty: true,
                          });
                        }}
                        disabled={isApplying || isPending || isError}
                      >
                        <FormControl>
                          <SelectTrigger
                            id="bulk-feedback-definition"
                            data-testid="annotate-bulk-score-select"
                          >
                            <SelectValue placeholder="Select a feedback definition" />
                          </SelectTrigger>
                        </FormControl>
                        <SelectContent>
                          {filteredDefinitions.map((item) => (
                            <SelectItem
                              key={item.id}
                              value={item.id}
                              data-testid={`annotate-bulk-score-select-option-${item.name}`}
                            >
                              <span>{item.name}</span>
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                      <FormMessage />
                      {isError && (
                        <p role="alert">Unable to load feedback definitions.</p>
                      )}
                      {!isPending && !isError && !definitions.length && (
                        <p>No feedback definitions available.</p>
                      )}
                    </FormItem>
                  )}
                />
                {definition?.type === FEEDBACK_DEFINITION_TYPE.numerical && (
                  <FormField
                    control={form.control}
                    name="value"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>
                          <span>
                            Score ({definition.details.min}–
                            {definition.details.max})
                          </span>
                        </FormLabel>
                        <FormControl>
                          <Input
                            id="bulk-feedback-value"
                            type="number"
                            step="any"
                            min={definition.details.min}
                            max={definition.details.max}
                            value={field.value}
                            onChange={field.onChange}
                            data-testid="annotate-bulk-score-input"
                          />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                )}
                {categories.length > 0 && (
                  <FormField
                    control={form.control}
                    name="categoryId"
                    render={({ field }) => (
                      <FormItem>
                        <FormControl>
                          <ToggleGroup
                            type="single"
                            variant="outline"
                            value={field.value}
                            onValueChange={field.onChange}
                            disabled={isApplying}
                            className="flex-wrap justify-start"
                            aria-label="Score"
                          >
                            {categories.map((category) => (
                              <ToggleGroupItem
                                key={category.id}
                                value={category.id}
                                data-testid={`annotate-bulk-category-toggle-${category.id}`}
                              >
                                <span>
                                  {definition?.type ===
                                  FEEDBACK_DEFINITION_TYPE.boolean
                                    ? category.label
                                    : `${category.label} (${category.value})`}
                                </span>
                              </ToggleGroupItem>
                            ))}
                          </ToggleGroup>
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                )}
                <FormField
                  control={form.control}
                  name="reason"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Reason (optional)</FormLabel>
                      <FormControl>
                        <Textarea
                          id="bulk-feedback-reason"
                          value={field.value}
                          onChange={field.onChange}
                          placeholder="Add a reason..."
                          data-testid="annotate-bulk-reason-input"
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </fieldset>
            </form>
          </Form>
        </DialogAutoScrollBody>
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            disabled={isApplying}
            onClick={handleClose}
          >
            Cancel
          </Button>
          <Button
            type="submit"
            form="annotate-bulk-form"
            disabled={!isValid || !rows.length || isApplying}
            data-testid="annotate-bulk-apply-button"
          >
            {isApplying && <Spinner size="small" className="mr-2" />}
            <span>{isApplying ? "Applying..." : "Apply"}</span>
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AnnotateTracesOrSpansDialog;

import React, { useCallback, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import get from "lodash/get";

import { cn } from "@/lib/utils";

import { Button } from "@/ui/button";
import { Sheet, SheetContent, SheetTopBar } from "@/ui/sheet";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/ui/form";
import { Input } from "@/ui/input";
import { Textarea } from "@/ui/textarea";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import FeedbackDefinitionsSelectBox from "@/v2/pages-shared/annotation-queues/FeedbackDefinitionsSelectBox";
import FeedbackDefinitionChips from "@/v2/pages-shared/annotation-queues/FeedbackDefinitionChips";
import FeedbackScoreConditions, {
  DEFAULT_UNWINDOWED_CONDITION,
} from "@/v2/pages-shared/feedback-score-conditions/FeedbackScoreConditions";
import { ScoreSource } from "@/v2/pages-shared/experiments/FeedbackDefinitionsAndScoresSelectBox/FeedbackDefinitionsAndScoresSelectBox";
import { Switch } from "@/ui/switch";
import { ArrowUpRight, Zap } from "lucide-react";

import {
  ANNOTATION_QUEUE_SCOPE,
  AnnotationQueue,
} from "@/types/annotation-queues";
import useAnnotationQueueCreateMutation from "@/api/annotation-queues/useAnnotationQueueCreateMutation";
import useAnnotationQueueUpdateMutation from "@/api/annotation-queues/useAnnotationQueueUpdateMutation";
import { DEFAULT_LOCK_TIMEOUT_SECONDS } from "@/lib/annotation-queues";
import { Separator } from "@/ui/separator";
import { Description } from "@/ui/description";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { buildDocsUrl } from "@/v2/lib/utils";
import { usePermissions } from "@/contexts/PermissionsContext";

const QUEUE_DOCS_LINK = buildDocsUrl("/evaluation/advanced/annotation_queues");

const SCOPE_OPTIONS = [
  {
    value: ANNOTATION_QUEUE_SCOPE.TRACE,
    label: "Traces",
  },
  {
    value: ANNOTATION_QUEUE_SCOPE.THREAD,
    label: "Threads",
  },
];

const formSchema = z
  .object({
    project_id: z.string().min(1, "Project is required"),
    name: z
      .string()
      .trim()
      .min(1, "Name is required")
      .max(255, "Name cannot exceed 255 characters"),
    description: z.string().optional(),
    instructions: z.string().optional(),
    scope: z.nativeEnum(ANNOTATION_QUEUE_SCOPE),
    comments_enabled: z.boolean(),
    feedback_definition_names: z.array(z.string()).default([]),
    annotators_per_item: z.coerce.number().int().min(1).default(1),
    lock_timeout_minutes: z.coerce
      .number()
      .int()
      .min(1)
      .max(60)
      .default(DEFAULT_LOCK_TIMEOUT_SECONDS / 60),
    automation_enabled: z.boolean().default(false),
    // Held in the shared condition shape (name/operator/threshold) so the builder can be reused as-is,
    // then mapped to the API's score/operator/value on submit. Only validated when the toggle is on:
    // conditions left half-filled while automation is off must not block saving the queue.
    automation_groups: z
      .array(
        z.object({
          conditions: z.array(
            z.object({
              name: z.string(),
              operator: z.enum([">", "<", "="]),
              threshold: z.string(),
            }),
          ),
        }),
      )
      .default([]),
  })
  .superRefine((data, ctx) => {
    if (!data.automation_enabled) {
      return;
    }

    const groups = data.automation_groups;
    if (!groups.length || groups.every((g) => !g.conditions.length)) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: "At least one condition is required",
        path: ["automation_groups"],
      });
      return;
    }

    groups.forEach((group, groupIndex) => {
      group.conditions.forEach((condition, conditionIndex) => {
        const base = [
          "automation_groups",
          groupIndex,
          "conditions",
          conditionIndex,
        ];
        if (!condition.name) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: "Select a score",
            path: [...base, "name"],
          });
        }
        if (
          condition.threshold === "" ||
          Number.isNaN(Number(condition.threshold))
        ) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: "Enter a number",
            path: [...base, "threshold"],
          });
        }
      });
    });
  });

type FormData = z.infer<typeof formSchema>;

type AddEditAnnotationQueueDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  onQueueCreated?: (queue: Partial<AnnotationQueue>) => void;
  projectId: string;
  scope?: ANNOTATION_QUEUE_SCOPE;
  queue?: AnnotationQueue;
  /** Start with automation switched on — used when the form is opened from the 'Add automation' menu. */
  expandAutomation?: boolean;
};

const AddEditAnnotationQueueDialog: React.FunctionComponent<
  AddEditAnnotationQueueDialogProps
> = ({
  open,
  setOpen,
  projectId,
  scope,
  onQueueCreated,
  queue: defaultQueue,
  expandAutomation,
}) => {
  const {
    permissions: { canCreateAnnotationQueues, canEditAnnotationQueues },
  } = usePermissions();

  const [isNestedDialogOpen, setIsNestedDialogOpen] = useState(false);

  const form = useForm<FormData>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      name: defaultQueue?.name || "",
      instructions: defaultQueue?.instructions || "",
      project_id: defaultQueue?.project_id || projectId || "",
      scope: defaultQueue?.scope || scope || ANNOTATION_QUEUE_SCOPE.TRACE,
      feedback_definition_names: defaultQueue?.feedback_definition_names || [],
      comments_enabled: defaultQueue?.comments_enabled || true,
      annotators_per_item: defaultQueue?.annotators_per_item || 1,
      lock_timeout_minutes:
        (defaultQueue?.lock_timeout_seconds ?? DEFAULT_LOCK_TIMEOUT_SECONDS) /
        60,
      // Off by default, except when the form was opened from the 'Add automation' menu, where
      // configuring automation is the whole point of the visit.
      automation_enabled:
        defaultQueue?.automation?.enabled ?? Boolean(expandAutomation),
      automation_groups: defaultQueue?.automation?.conditions.groups.map(
        (group) => ({
          conditions: group.conditions.map((condition) => ({
            name: condition.score,
            operator: condition.operator,
            threshold: String(condition.value),
          })),
        }),
      ) ?? [{ conditions: [{ ...DEFAULT_UNWINDOWED_CONDITION }] }],
    },
  });

  const { mutate: createMutate, isPending: isCreatePending } =
    useAnnotationQueueCreateMutation();
  const { mutate: updateMutate, isPending: isUpdatePending } =
    useAnnotationQueueUpdateMutation();
  const isSubmitting = isCreatePending || isUpdatePending;

  const automationEnabled = form.watch("automation_enabled");
  // Automation matches on the scores of whatever the queue collects, so the score options and the copy
  // follow the scope: thread scores are a different set of names from trace scores.
  const isThreadScope = form.watch("scope") === ANNOTATION_QUEUE_SCOPE.THREAD;
  const automationGroupsError = get(
    form.formState.errors,
    ["automation_groups", "message"],
    undefined,
  ) as string | undefined;

  const isEdit = Boolean(defaultQueue);
  const title = isEdit ? "Edit annotation queue" : "Create annotation queue";
  const submitText = isEdit
    ? "Update annotation queue"
    : "Create annotation queue";

  const getQueue = useCallback(() => {
    const formData = form.getValues();
    const {
      lock_timeout_minutes,
      automation_enabled,
      automation_groups,
      ...rest
    } = formData;

    return {
      ...rest,
      name: formData.name.trim(),
      project_id: formData.project_id,
      lock_timeout_seconds: lock_timeout_minutes * 60,
      automation: {
        enabled: automation_enabled,
        conditions: {
          groups: automation_groups.map((group) => ({
            conditions: group.conditions.map((condition) => ({
              score: condition.name,
              operator: condition.operator,
              value: Number(condition.threshold),
            })),
          })),
        },
      },
    };
  }, [form]);

  const onQueueCreatedEdited = useCallback(
    (queue: Partial<AnnotationQueue>) => {
      if (onQueueCreated) {
        onQueueCreated(queue);
      }
    },
    [onQueueCreated],
  );

  const createQueue = useCallback(() => {
    createMutate(
      {
        annotationQueue: getQueue(),
      },
      {
        onSuccess: (queue) => {
          onQueueCreatedEdited(queue);
          setOpen(false);
        },
      },
    );
  }, [createMutate, getQueue, onQueueCreatedEdited, setOpen]);

  const editQueue = useCallback(() => {
    updateMutate(
      {
        annotationQueue: {
          id: defaultQueue?.id || "",
          ...getQueue(),
        },
      },
      {
        onSuccess: (queue) => {
          onQueueCreatedEdited(queue);
          setOpen(false);
        },
      },
    );
  }, [updateMutate, defaultQueue?.id, getQueue, onQueueCreatedEdited, setOpen]);

  const onSubmit = useCallback(
    () => (isEdit ? editQueue() : createQueue()),
    [isEdit, editQueue, createQueue],
  );

  return (
    <Sheet
      open={
        open && (isEdit ? canEditAnnotationQueues : canCreateAnnotationQueues)
      }
      onOpenChange={setOpen}
    >
      <SheetContent
        side="right"
        className="flex w-full max-w-none flex-col p-0 sm:max-w-[800px]"
        // A select or nested dialog opened from inside the form must not be treated as an outside
        // click, or configuring a field would close the whole form.
        blockOverlayClose={isNestedDialogOpen}
        header={
          <SheetTopBar variant="form" title={title}>
            <Button variant="outline" size="2xs" asChild>
              <a href={QUEUE_DOCS_LINK} target="_blank" rel="noreferrer">
                Docs
                <ArrowUpRight className="ml-1 size-3" />
              </a>
            </Button>
          </SheetTopBar>
        }
      >
        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-3">
          <Form {...form}>
            <form
              onSubmit={form.handleSubmit(onSubmit)}
              className="flex flex-col gap-4"
            >
              <FormField
                control={form.control}
                name="name"
                render={({ field, formState }) => {
                  const validationErrors = get(formState.errors, ["name"]);
                  return (
                    <FormItem>
                      <FormLabel>Name</FormLabel>
                      <FormControl>
                        <Input
                          dimension="sm"
                          className={cn({
                            "border-destructive": Boolean(
                              validationErrors?.message,
                            ),
                          })}
                          placeholder="Annotation queue name"
                          {...field}
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  );
                }}
              />
              <FormField
                control={form.control}
                name="scope"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Scope</FormLabel>
                    <FormControl>
                      <ToggleGroup
                        type="single"
                        variant="ghost"
                        value={field.value}
                        onValueChange={(value) =>
                          value && field.onChange(value)
                        }
                        disabled={isEdit || Boolean(scope)}
                        className="w-full"
                      >
                        {SCOPE_OPTIONS.map((option) => (
                          <ToggleGroupItem
                            key={option.value}
                            value={option.value}
                            size="sm"
                            // bg-muted is this theme's #F1F5F9 — the design's active fill — and it
                            // follows dark mode, which a literal hex would not.
                            className="comet-body-xs flex-1 hover:bg-upload-icon-bg data-[state=on]:bg-muted data-[state=on]:text-foreground"
                          >
                            {option.label}
                          </ToggleGroupItem>
                        ))}
                      </ToggleGroup>
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField
                control={form.control}
                name="instructions"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Instructions (optional)</FormLabel>
                    <FormControl>
                      <Textarea
                        placeholder="Add instructions for annotators"
                        className="min-h-14"
                        {...field}
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField
                control={form.control}
                name="feedback_definition_names"
                render={({ field, formState }) => {
                  const validationErrors = get(formState.errors, [
                    "feedback_definition_names",
                  ]);
                  const selected = field.value ?? [];

                  return (
                    <FormItem>
                      <FormLabel>Feedback scores (optional)</FormLabel>
                      <FormControl>
                        <FeedbackDefinitionsSelectBox
                          value={selected}
                          onChange={field.onChange}
                          valueField="name"
                          multiselect
                          showSelectAll
                          onInnerDialogOpenChange={setIsNestedDialogOpen}
                          renderTitle={(options) => (
                            <FeedbackDefinitionChips
                              names={options.map((option) => option.label)}
                              onRemove={(name) =>
                                field.onChange(
                                  selected.filter((item) => item !== name),
                                )
                              }
                            />
                          )}
                          className={cn("h-8", {
                            "border-destructive": Boolean(
                              validationErrors?.message,
                            ),
                          })}
                        />
                      </FormControl>
                      <Description>
                        Select which feedback scores annotators can use when
                        evaluating items in this queue
                      </Description>
                      <FormMessage />
                    </FormItem>
                  );
                }}
              />
              <div className="flex items-start gap-4">
                <FormField
                  control={form.control}
                  name="annotators_per_item"
                  render={({ field }) => (
                    <FormItem className="flex-1">
                      <FormLabel>
                        Annotators per item{" "}
                        <ExplainerIcon
                          className="inline"
                          description="Set how many annotators must score an item before it is marked as complete."
                        />
                      </FormLabel>
                      <FormControl>
                        <Input
                          dimension="sm"
                          type="number"
                          min={1}
                          {...field}
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
                <FormField
                  control={form.control}
                  name="lock_timeout_minutes"
                  render={({ field }) => (
                    <FormItem className="flex-1">
                      <FormLabel>
                        Lock timeout{" "}
                        <ExplainerIcon
                          className="inline"
                          description="Set how long an item stays reserved while an annotator reviews it. After this time, it becomes available to another annotator."
                        />
                      </FormLabel>
                      <FormControl>
                        {/* The unit lives in the field rather than the label, per the design. It is
                            decoration over a plain number input, so it must not swallow clicks. */}
                        <div className="relative">
                          <Input
                            dimension="sm"
                            type="number"
                            min={1}
                            max={60}
                            className="pr-12"
                            {...field}
                          />
                          <span className="comet-body-s pointer-events-none absolute inset-y-0 right-8 flex items-center text-light-slate">
                            min
                          </span>
                        </div>
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>
              <Separator orientation="horizontal" className="my-0" />
              <div className="overflow-hidden rounded-md border border-border bg-soft-background">
                <div
                  className={cn(
                    "flex flex-col gap-1.5 p-3",
                    automationEnabled && "border-b border-border",
                  )}
                >
                  <div className="flex items-center gap-2">
                    <span className="flex h-5 items-center justify-center rounded-[4px] bg-lime-400 px-1">
                      <Zap className="size-3 text-background" />
                    </span>
                    <span className="comet-body-s-accented">Automation</span>
                  </div>
                  <div className="flex items-center gap-1.5">
                    <div className="comet-body-s min-w-0 flex-1">
                      Set conditions to automatically add matching{" "}
                      {isThreadScope ? "threads" : "traces"} to this queue
                    </div>
                    <FormField
                      control={form.control}
                      name="automation_enabled"
                      render={({ field }) => (
                        <FormItem className="shrink-0">
                          <FormControl>
                            <Switch
                              checked={field.value}
                              onCheckedChange={field.onChange}
                              aria-label="Enable automation"
                            />
                          </FormControl>
                        </FormItem>
                      )}
                    />
                  </div>
                </div>
                {automationEnabled && (
                  <div className="p-3">
                    <FeedbackScoreConditions
                      form={form}
                      groupsPath="automation_groups"
                      scoreSource={
                        isThreadScope ? ScoreSource.THREADS : ScoreSource.TRACES
                      }
                      projectId={projectId}
                      // Automation compares one entity's score, so equality is meaningful here in a
                      // way it is not for an alert's windowed aggregate.
                      operators={[">", "<", "="]}
                      groupIconClassName="bg-lime-400"
                      minimumMessage="Can't remove — automation needs at least one group with at least one condition."
                    />
                    {automationGroupsError && (
                      <p className="mt-1.5 text-[0.8rem] font-medium text-destructive">
                        {automationGroupsError}
                      </p>
                    )}
                  </div>
                )}
              </div>
            </form>
          </Form>
        </div>
        <div className="flex items-center gap-2 border-t border-border px-5 py-3">
          <Button
            type="submit"
            size="sm"
            disabled={isSubmitting}
            onClick={form.handleSubmit(onSubmit)}
          >
            {submitText}
          </Button>
          <Button variant="outline" size="sm" onClick={() => setOpen(false)}>
            Cancel
          </Button>
        </div>
      </SheetContent>
    </Sheet>
  );
};

export default AddEditAnnotationQueueDialog;

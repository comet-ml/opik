import React, { useCallback, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import get from "lodash/get";
import uniq from "lodash/uniq";

import { cn } from "@/lib/utils";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";

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
import { Checkbox } from "@/ui/checkbox";
import { Input } from "@/ui/input";
import { Textarea } from "@/ui/textarea";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import FeedbackDefinitionsSelectBox from "@/v2/pages-shared/annotation-queues/FeedbackDefinitionsSelectBox";
import FeedbackDefinitionChips from "@/v2/pages-shared/annotation-queues/FeedbackDefinitionChips";
import AutomationZapIcon from "@/v2/pages-shared/annotation-queues/AutomationZapIcon";
import StepperField from "@/v2/pages-shared/annotation-queues/StepperField";
import AutomationFilterBlocks from "@/v2/pages-shared/annotation-queues/AutomationFilterBlocks";
import useAutomationChipDefinitions from "@/v2/pages-shared/annotation-queues/useAutomationChipDefinitions";
import {
  fromAutomationFilters,
  splitAutomationFilters,
  incompleteAutomationBlocks,
  toAutomationFilters,
} from "@/v2/pages-shared/annotation-queues/automationFilters";
import { Switch } from "@/ui/switch";
import { ArrowUpRight, ChevronRight } from "lucide-react";
import AnnotatorViewCard from "./AnnotatorView/AnnotatorViewCard";
import AnnotatorViewPreviewPage from "./AnnotatorView/AnnotatorViewPreviewPage";
import useAnnotatorViewEditor from "./AnnotatorView/useAnnotatorViewEditor";

import {
  AnnotationQueueAutomation,
  AnnotationQueueAutomationFilter,
  ANNOTATION_QUEUE_SCOPE,
  AnnotationQueue,
} from "@/types/annotation-queues";
import useAnnotationQueueCreateMutation from "@/api/annotation-queues/useAnnotationQueueCreateMutation";
import useAnnotationQueueUpdateMutation from "@/api/annotation-queues/useAnnotationQueueUpdateMutation";
import useAnnotationQueueById from "@/api/annotation-queues/useAnnotationQueueById";
import Loader from "@/shared/Loader/Loader";
import { DEFAULT_LOCK_TIMEOUT_SECONDS } from "@/lib/annotation-queues";
import { Separator } from "@/ui/separator";
import { Description } from "@/ui/description";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { buildDocsUrl } from "@/v2/lib/utils";
import { usePermissions } from "@/contexts/PermissionsContext";

const DEFAULT_AUTOMATION_MAX_ITEMS = 1000;

const QUEUE_DOCS_LINK = buildDocsUrl("/evaluation/advanced/annotation_queues");

const LABEL_CLASS = "px-0.5 pb-0.5";

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

// Exported for its own tests: the cap's rules live in the superRefine below, and they are worth
// asserting without standing up the whole sheet.
export const formSchema = z
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
    feedback_definition_names: z.array(z.string()),
    annotators_per_item: z.coerce
      .number<number | string>()
      .int("Must be a whole number")
      .min(1, "Must be at least 1"),
    lock_timeout_minutes: z.coerce
      .number<number | string>()
      .int("Must be a whole number")
      .min(1, "Must be between 1 and 60 minutes")
      .max(60, "Must be between 1 and 60 minutes"),
    automation_enabled: z.boolean(),
    automation_cap_enabled: z.boolean(),
    // A string like the thresholds: a number field's raw text is what validation needs to see.
    automation_max_items: z.string(),
    // One block per filter field, in the order they were added; values are the Logs chip values, mapped
    // to the API's filters on submit. Only validated when the toggle is on: blocks left empty while
    // automation is off must not block saving the queue.
    automation_filters: z.object({
      ids: z.array(z.string()),
      values: z.record(z.string(), z.any()),
    }),
    // Groups after the first, which the dialog does not edit: only the API can create more than one. They
    // are sent back unchanged so editing such a queue here does not drop its other groups.
    automation_other_filter_groups: z.array(z.array(z.any())),
    // Filters of the first group on a field the dialog has no block for. Sent back with the group unchanged.
    automation_hidden_filters: z.array(z.any()),
  })
  .superRefine((data, ctx) => {
    if (!data.automation_enabled) {
      return;
    }

    if (data.automation_cap_enabled) {
      const max = Number(data.automation_max_items);
      if (
        data.automation_max_items === "" ||
        !Number.isInteger(max) ||
        max < 1
      ) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: "Enter a whole number of 1 or more",
          path: ["automation_max_items"],
        });
      }
    }

    // A block only holds a value once it is a complete filter, so no values means nothing to match on.
    if (
      Object.keys(data.automation_filters.values).length === 0 &&
      data.automation_hidden_filters.length === 0 &&
      data.automation_other_filter_groups.length === 0
    ) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: "At least one filter is required",
        path: ["automation_filters"],
      });
    }
  });

type FormData = z.output<typeof formSchema>;
type FormInput = z.input<typeof formSchema>;

type AddEditAnnotationQueueDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  onQueueCreated?: (queue: Partial<AnnotationQueue>) => void;
  projectId: string;
  scope?: ANNOTATION_QUEUE_SCOPE;
  queue?: AnnotationQueue;
};

/**
 * The automation field of a queue payload, or nothing at all.
 *
 * Three states, because the API reads three: an absent automation leaves what is stored alone, a
 * present one without filters reuses the stored filters, and reusing them fails when there are none
 * stored. So a queue that never had automation has to send nothing rather than a disabled
 * automation — otherwise creating a queue, or editing one the SDK made, is rejected. With the feature
 * off nothing is sent either, whatever the queue already holds.
 *
 * The ceiling follows the same rule: an absent one is left alone, so unticking the cap sends
 * clear_max_items_in_queue rather than a null the API could not tell from "leave it".
 */
export const buildAutomationPayload = ({
  isFeatureEnabled,
  hasStoredAutomation,
  enabled,
  capEnabled,
  maxItems,
  filterGroups,
}: {
  isFeatureEnabled: boolean;
  hasStoredAutomation: boolean;
  enabled: boolean;
  capEnabled: boolean;
  maxItems: string;
  filterGroups: AnnotationQueueAutomationFilter[][];
}): AnnotationQueueAutomation | undefined => {
  if (!isFeatureEnabled) return undefined;

  // The cap control is hidden while automation is off, but its state is not, so a save that unticks the
  // cap and turns automation off at once has to carry the removal too.
  if (!enabled) {
    if (!hasStoredAutomation) return undefined;

    return capEnabled
      ? { enabled: false }
      : { enabled: false, clear_max_items_in_queue: true };
  }

  return {
    enabled: true,
    // Absent means "keep what is stored", so removing a ceiling has to be said outright.
    ...(capEnabled
      ? { max_items_in_queue: Number(maxItems) }
      : { clear_max_items_in_queue: true }),
    filter_groups: filterGroups.filter((group) => group.length > 0),
  };
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
}) => {
  const {
    permissions: { canCreateAnnotationQueues, canEditAnnotationQueues },
  } = usePermissions();

  const [isNestedDialogOpen, setIsNestedDialogOpen] = useState(false);
  const [page, setPage] = useState<"form" | "preview">("form");
  // Set by the first save attempt, so blocks are only flagged once the user has tried to save them.
  const [isCheckingBlocks, setIsCheckingBlocks] = useState(false);

  const isAutomationEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.ANNOTATION_QUEUE_AUTOMATION_ENABLED,
  );

  const initialScope =
    defaultQueue?.scope || scope || ANNOTATION_QUEUE_SCOPE.TRACE;
  const initialProjectId = defaultQueue?.project_id || projectId || "";
  const initialDefinitions = useAutomationChipDefinitions(
    initialProjectId,
    initialScope,
  );
  const storedFirstGroup = splitAutomationFilters(
    initialDefinitions,
    defaultQueue?.automation?.filter_groups?.[0],
  );

  const form = useForm<FormInput, unknown, FormData>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      name: defaultQueue?.name || "",
      instructions: defaultQueue?.instructions || "",
      project_id: initialProjectId,
      scope: initialScope,
      feedback_definition_names: defaultQueue?.feedback_definition_names || [],
      comments_enabled: defaultQueue?.comments_enabled ?? true,
      annotators_per_item: defaultQueue?.annotators_per_item || 1,
      lock_timeout_minutes:
        (defaultQueue?.lock_timeout_seconds ?? DEFAULT_LOCK_TIMEOUT_SECONDS) /
        60,
      // With the feature off the form behaves as if automation never existed: the switch is never on,
      // so no field the dialog does not render can fail validation on a queue configured while it was
      // on. Nothing stored is touched — the payload carries no automation at all.
      automation_enabled: isAutomationEnabled
        ? (defaultQueue?.automation?.enabled ?? false)
        : false,
      automation_cap_enabled:
        defaultQueue?.automation?.max_items_in_queue != null,
      automation_max_items: String(
        defaultQueue?.automation?.max_items_in_queue ??
          DEFAULT_AUTOMATION_MAX_ITEMS,
      ),
      automation_filters: (() => {
        const stored = storedFirstGroup.shown;
        const values = fromAutomationFilters(initialDefinitions, stored);
        // Blocks reopen in the order their filters were saved, which is the order they were added in.
        const firstStored = (field: string) =>
          stored.findIndex((filter) => filter.field === field);
        return {
          ids: initialDefinitions
            .filter((def) => def.id in values)
            .sort((a, b) => firstStored(a.field) - firstStored(b.field))
            .map((def) => def.id),
          values,
        };
      })(),
      automation_other_filter_groups:
        defaultQueue?.automation?.filter_groups?.slice(1) ?? [],
      automation_hidden_filters: storedFirstGroup.kept,
    },
  });

  const { mutate: createMutate, isPending: isCreatePending } =
    useAnnotationQueueCreateMutation();
  const { mutate: updateMutate, isPending: isUpdatePending } =
    useAnnotationQueueUpdateMutation();
  const isSubmitting = isCreatePending || isUpdatePending;
  const { data: fullQueue, isError: isQueueError } = useAnnotationQueueById(
    { annotationQueueId: defaultQueue?.id ?? "" },
    { enabled: open && Boolean(defaultQueue) },
  );
  const isViewLoading = Boolean(defaultQueue) && !fullQueue && !isQueueError;
  const isViewFailed = Boolean(defaultQueue) && !fullQueue && isQueueError;

  const automationEnabled = form.watch("automation_enabled");
  const capEnabled = form.watch("automation_cap_enabled");
  const capError = get(
    form.formState.errors,
    ["automation_max_items", "message"],
    undefined,
  ) as string | undefined;
  // Automation filters whatever the queue collects, so the filter fields and the copy follow the scope:
  // threads are filtered on a different set of fields from traces.
  const isThreadScope = form.watch("scope") === ANNOTATION_QUEUE_SCOPE.THREAD;
  const automationDefinitions = useAutomationChipDefinitions(
    form.watch("project_id"),
    form.watch("scope"),
  );
  const annotatorViewEditor = useAnnotatorViewEditor({
    open,
    view: fullQueue?.annotator_view,
    projectId: form.watch("project_id"),
    queueId: defaultQueue?.id,
    queueName: form.watch("name"),
    instructions: form.watch("instructions") ?? "",
    feedbackDefinitionNames: form.watch("feedback_definition_names"),
    isThreadScope,
  });
  const { annotatorView } = annotatorViewEditor;
  const startAnnotatorViewOllie = () => {
    setPage("preview");
    annotatorViewEditor.startOllie();
  };
  // Set once from the stored automation and never edited here; the dialog remounts for every queue.
  const otherFilterGroupsCount = form.getValues(
    "automation_other_filter_groups",
  ).length;
  const hiddenFilters: AnnotationQueueAutomationFilter[] = form.getValues(
    "automation_hidden_filters",
  );
  const hiddenFiltersCount = hiddenFilters.length;
  // A field with a kept filter is not offered again: a second block for it would make the field's filters
  // unrepresentable as a whole, and all of them would be kept on the next open, the new one included.
  const hiddenFields = new Set(hiddenFilters.map((filter) => filter.field));
  const hiddenFieldLabels = uniq(
    hiddenFilters.map(
      (filter) =>
        initialDefinitions.find((def) => def.field === filter.field)?.label ??
        filter.field,
    ),
  ).join(", ");
  const automationFiltersError = get(
    form.formState.errors,
    ["automation_filters", "message"],
    undefined,
  ) as string | undefined;

  const isEdit = Boolean(defaultQueue);
  const title = isEdit ? "Edit annotation queue" : "Create annotation queue";
  const submitText = isEdit ? "Save changes" : "Create queue";

  const getQueue = useCallback(
    (formData: FormData) => {
      const {
        lock_timeout_minutes,
        automation_enabled,
        automation_filters,
        automation_other_filter_groups,
        automation_hidden_filters,
        automation_cap_enabled,
        automation_max_items,
        ...rest
      } = formData;

      return {
        ...rest,
        name: formData.name.trim(),
        project_id: formData.project_id,
        lock_timeout_seconds: lock_timeout_minutes * 60,
        automation: buildAutomationPayload({
          isFeatureEnabled: isAutomationEnabled,
          hasStoredAutomation: Boolean(defaultQueue?.automation),
          enabled: automation_enabled,
          capEnabled: automation_cap_enabled,
          maxItems: automation_max_items,
          filterGroups: [
            [
              ...toAutomationFilters(
                automationDefinitions,
                automation_filters.values,
              ),
              ...automation_hidden_filters,
            ],
            ...automation_other_filter_groups,
          ],
        }),
        ...(annotatorViewEditor.isUnsaved
          ? { annotator_view: annotatorView }
          : {}),
      };
    },
    [
      defaultQueue,
      isAutomationEnabled,
      automationDefinitions,
      annotatorView,
      annotatorViewEditor.isUnsaved,
    ],
  );

  const onQueueCreatedEdited = useCallback(
    (queue: Partial<AnnotationQueue>) => {
      if (onQueueCreated) {
        onQueueCreated(queue);
      }
    },
    [onQueueCreated],
  );

  const createQueue = useCallback(
    (formData: FormData) => {
      createMutate(
        {
          annotationQueue: getQueue(formData),
        },
        {
          onSuccess: (queue) => {
            onQueueCreatedEdited(queue);
            setOpen(false);
          },
        },
      );
    },
    [createMutate, getQueue, onQueueCreatedEdited, setOpen],
  );

  const editQueue = useCallback(
    (formData: FormData) => {
      updateMutate(
        {
          annotationQueue: {
            id: defaultQueue?.id || "",
            ...getQueue(formData),
          },
        },
        {
          onSuccess: (queue) => {
            onQueueCreatedEdited(queue);
            setOpen(false);
          },
        },
      );
    },
    [updateMutate, defaultQueue?.id, getQueue, onQueueCreatedEdited, setOpen],
  );

  const onSubmit = useCallback(
    (formData: FormData) => {
      if (
        formData.automation_enabled &&
        incompleteAutomationBlocks(
          automationDefinitions,
          formData.automation_filters,
        ).length > 0
      ) {
        setIsCheckingBlocks(true);
        setPage("form");
        return;
      }
      return isEdit ? editQueue(formData) : createQueue(formData);
    },
    [isEdit, editQueue, createQueue, automationDefinitions],
  );

  const handleSubmit = form.handleSubmit(onSubmit, () => {
    setIsCheckingBlocks(true);
    setPage("form");
  });

  return (
    <Sheet
      open={
        open && (isEdit ? canEditAnnotationQueues : canCreateAnnotationQueues)
      }
      onOpenChange={setOpen}
    >
      <SheetContent
        side="right"
        className="flex w-full max-w-none flex-col gap-0 p-0 sm:max-w-[800px]"
        // A select or nested dialog opened from inside the form must not be treated as an outside
        // click, or configuring a field would close the whole form.
        // A form with edits closes only through X or Cancel, never by a stray click on the overlay.
        blockOverlayClose={
          isNestedDialogOpen ||
          form.formState.isDirty ||
          annotatorViewEditor.isUnsaved
        }
        header={
          <SheetTopBar
            variant="form"
            title={
              page === "preview" ? (
                <span className="flex items-center gap-1">
                  <button
                    type="button"
                    className="hover:text-muted-slate"
                    onClick={() => setPage("form")}
                  >
                    {title}
                  </button>
                  <ChevronRight className="size-3.5 text-light-slate" />
                  Annotator view
                </span>
              ) : (
                title
              )
            }
          >
            <Button
              variant="outline"
              size="2xs"
              className="rounded-[4px]"
              asChild
            >
              <a href={QUEUE_DOCS_LINK} target="_blank" rel="noreferrer">
                Docs
                <ArrowUpRight className="ml-1 size-3" />
              </a>
            </Button>
          </SheetTopBar>
        }
      >
        {page === "preview" && (
          <AnnotatorViewPreviewPage
            editor={annotatorViewEditor}
            onBack={() => setPage("form")}
          />
        )}
        <div
          className={cn(
            "min-h-0 flex-1 overflow-y-auto py-3 pl-[19px] pr-5",
            page !== "form" && "hidden",
          )}
        >
          <Form {...form}>
            <form onSubmit={handleSubmit} className="flex flex-col gap-4">
              <FormField
                control={form.control}
                name="name"
                render={({ field, formState }) => {
                  const validationErrors = get(formState.errors, ["name"]);
                  return (
                    <FormItem className="gap-1">
                      <FormLabel className={LABEL_CLASS}>Name</FormLabel>
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
                  <FormItem className="gap-1">
                    <FormLabel className={LABEL_CLASS}>Scope</FormLabel>
                    <FormControl>
                      <ToggleGroup
                        type="single"
                        variant="ghost"
                        value={field.value}
                        onValueChange={(value) => {
                          if (!value) return;
                          field.onChange(value);
                          // Trace and thread filters are different fields, so the blocks start over.
                          form.setValue("automation_filters", {
                            ids: [],
                            values: {},
                          });
                        }}
                        disabled={isEdit || Boolean(scope)}
                        className="w-full"
                      >
                        {SCOPE_OPTIONS.map((option) => (
                          <ToggleGroupItem
                            key={option.value}
                            value={option.value}
                            size="sm"
                            className="flex-1"
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
                  <FormItem className="gap-1">
                    <FormLabel className={LABEL_CLASS}>
                      Instructions (optional)
                    </FormLabel>
                    <FormControl>
                      <Textarea
                        placeholder="Add instructions for annotators"
                        className="h-14 min-h-14"
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
                    <FormItem className="gap-1">
                      <FormLabel className={LABEL_CLASS}>
                        Feedback scores (optional)
                      </FormLabel>
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
                    <FormItem className="flex-1 gap-1">
                      <FormLabel className={LABEL_CLASS}>
                        Annotators per item{" "}
                        <ExplainerIcon
                          className="inline"
                          description="Set how many annotators must score an item before it is marked as complete."
                        />
                      </FormLabel>
                      <FormControl>
                        <StepperField {...field} min={1} />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
                <FormField
                  control={form.control}
                  name="lock_timeout_minutes"
                  render={({ field }) => (
                    <FormItem className="flex-1 gap-1">
                      <FormLabel className={LABEL_CLASS}>
                        Lock timeout{" "}
                        <ExplainerIcon
                          className="inline"
                          description="Set how long an item stays reserved while an annotator reviews it. After this time, it becomes available to another annotator."
                        />
                      </FormLabel>
                      <FormControl>
                        <StepperField
                          {...field}
                          min={1}
                          max={60}
                          suffix="min"
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>
              {isAutomationEnabled && (
                <>
                  <Separator orientation="horizontal" className="my-1" />
                  <div className="overflow-hidden rounded-md border border-border bg-soft-background">
                    <div
                      className={cn(
                        // pb is 1px under p-3: what the frame actually renders.
                        "flex flex-col gap-1.5 p-3 pb-[11px] pl-[13px]",
                        automationEnabled && "border-b border-border",
                      )}
                    >
                      <div className="flex items-center justify-between gap-2">
                        <div className="flex items-center gap-1.5">
                          <span className="flex h-5 items-center justify-center rounded-[4px] bg-lime-400 px-1">
                            <AutomationZapIcon className="text-black" />
                          </span>
                          <span className="comet-body-s-accented">
                            Add {isThreadScope ? "threads" : "traces"}{" "}
                            automatically
                          </span>
                        </div>
                        <FormField
                          control={form.control}
                          name="automation_enabled"
                          render={({ field }) => (
                            <FormItem className="shrink-0">
                              <FormControl>
                                <Switch
                                  size="xs"
                                  // The frame's switch is 24x14 with a 12px thumb; the nearest stock variant
                                  // is 28x16, so track, thumb travel and off colour are set here.
                                  className="h-[14px] w-6 border data-[state=unchecked]:bg-slate-300 [&>span]:size-3 [&>span]:data-[state=checked]:translate-x-[10px]"
                                  checked={field.value}
                                  onCheckedChange={field.onChange}
                                  aria-label="Enable automation"
                                />
                              </FormControl>
                            </FormItem>
                          )}
                        />
                      </div>
                      <div className="comet-body-xs">
                        {isThreadScope ? "Threads" : "Traces"} will be
                        automatically added to the queue when they match every
                        filter.
                      </div>
                    </div>
                    {automationEnabled && (
                      <div className="p-2">
                        <FormField
                          control={form.control}
                          name="automation_filters"
                          render={({ field }) => (
                            <AutomationFilterBlocks
                              definitions={automationDefinitions.filter(
                                (def) => !hiddenFields.has(def.field),
                              )}
                              value={field.value}
                              onChange={field.onChange}
                              incompleteIds={
                                isCheckingBlocks
                                  ? incompleteAutomationBlocks(
                                      automationDefinitions,
                                      field.value,
                                    )
                                  : []
                              }
                            />
                          )}
                        />
                        {automationFiltersError && (
                          <p className="comet-body-s mt-1.5 px-0.5 text-destructive">
                            {automationFiltersError}
                          </p>
                        )}
                        {hiddenFiltersCount > 0 && (
                          <p className="comet-body-xs mt-2 px-0.5 text-muted-slate">
                            {hiddenFiltersCount === 1
                              ? `1 more filter set through the API (${hiddenFieldLabels}) also applies and can't be edited here. It is kept as it is.`
                              : `${hiddenFiltersCount} more filters set through the API (${hiddenFieldLabels}) also apply and can't be edited here. They are kept as they are.`}
                          </p>
                        )}
                        {otherFilterGroupsCount > 0 && (
                          <p className="comet-body-xs mt-2 px-0.5 text-muted-slate">
                            {isThreadScope ? "Threads" : "Traces"} matching{" "}
                            {otherFilterGroupsCount} other filter{" "}
                            {otherFilterGroupsCount === 1 ? "group" : "groups"}{" "}
                            set through the API are added too. They are kept as
                            they are.
                          </p>
                        )}
                        <p className="comet-body-xs mt-2 px-0.5 text-light-slate">
                          {isThreadScope ? "Threads" : "Traces"} from
                          experiments, the playground and optimizations are
                          never added.
                        </p>
                        <div className="mt-3 flex flex-col gap-1 border-t border-border pt-3">
                          <div className="flex items-center gap-1.5">
                            <FormField
                              control={form.control}
                              name="automation_cap_enabled"
                              render={({ field }) => (
                                <FormItem className="flex-row items-center gap-1.5">
                                  <FormControl>
                                    <Checkbox
                                      id="automation-cap-enabled"
                                      checked={field.value}
                                      onCheckedChange={(checked) =>
                                        field.onChange(checked === true)
                                      }
                                    />
                                  </FormControl>
                                  <FormLabel
                                    htmlFor="automation-cap-enabled"
                                    className="comet-body-s cursor-pointer font-normal"
                                  >
                                    Cap automatically added items at
                                  </FormLabel>
                                </FormItem>
                              )}
                            />
                            <FormField
                              control={form.control}
                              name="automation_max_items"
                              render={({ field }) => (
                                <FormItem>
                                  <FormControl>
                                    <Input
                                      {...field}
                                      type="number"
                                      min={1}
                                      step={1}
                                      dimension="xs"
                                      disabled={!capEnabled}
                                      aria-label="Cap automatically added items at"
                                      className={cn(
                                        "w-[87px] text-right",
                                        capError && "border-destructive",
                                      )}
                                    />
                                  </FormControl>
                                </FormItem>
                              )}
                            />
                          </div>
                          <p className="comet-body-xs pl-[22px] text-light-slate">
                            Automation stops adding once the queue holds this
                            many items. Adding by hand is unaffected.
                          </p>
                          {capError && (
                            <p className="comet-body-xs pl-[26px] text-destructive">
                              {capError}
                            </p>
                          )}
                        </div>
                      </div>
                    )}
                  </div>
                </>
              )}
              {isViewLoading ? (
                <Loader className="min-h-32 rounded-md border border-border" />
              ) : isViewFailed ? (
                <div className="comet-body-s rounded-md border border-border p-3 text-muted-slate">
                  Couldn&apos;t load the annotator view.
                </div>
              ) : (
                <AnnotatorViewCard
                  editor={annotatorViewEditor}
                  onPreview={() => setPage("preview")}
                  onStartOllie={startAnnotatorViewOllie}
                />
              )}
            </form>
          </Form>
        </div>
        {/* 11 top and 12 bottom, not 12/12: the frame's footer is 56px, which the rule plus a 32px
            button leaves 23 to split. Both must stay whole numbers — the symmetric 11.5 that fits
            put the buttons on y=916.5, and a 1px border straddling two rows renders as two lighter
            ones instead of one crisp line. */}
        <div className="flex items-center justify-end gap-2 border-t border-border pb-3 pl-[19px] pr-5 pt-[11px]">
          {/* Both buttons are anchored to the right edge, so the frame's widths (71 and 115) are what
              put every edge on a whole pixel. Chrome measures Inter a fraction narrower than Figma
              does, which otherwise leaves each button ~0.5px short and every edge left of the sheet's
              padding on a fraction. min-w rather than w, so a longer label still grows the button. */}
          <Button
            variant="outline"
            size="sm"
            className="min-w-[71px] px-[11px]"
            onClick={() => setOpen(false)}
          >
            Cancel
          </Button>
          <Button
            type="submit"
            size="sm"
            disabled={isSubmitting || isViewLoading}
            className="min-w-[115px]"
            onClick={handleSubmit}
          >
            {submitText}
          </Button>
        </div>
      </SheetContent>
    </Sheet>
  );
};

export default AddEditAnnotationQueueDialog;

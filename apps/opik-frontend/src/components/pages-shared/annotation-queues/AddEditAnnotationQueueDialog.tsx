import React, { useCallback, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import get from "lodash/get";

import { cn } from "@/lib/utils";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import FeedbackDefinitionsSelectBox from "@/components/pages-shared/annotation-queues/FeedbackDefinitionsSelectBox";

import {
  ANNOTATION_QUEUE_SCOPE,
  AnnotationQueue,
} from "@/types/annotation-queues";
import useAnnotationQueueCreateMutation from "@/api/annotation-queues/useAnnotationQueueCreateMutation";
import useAnnotationQueueUpdateMutation from "@/api/annotation-queues/useAnnotationQueueUpdateMutation";
import { Separator } from "@/components/ui/separator";
import { Description } from "@/components/ui/description";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

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

const formSchema = z.object({
  project_id: z.string().min(1, "Project is required"),
  name: z
    .string()
    .min(1, "Name is required")
    .max(255, "Name cannot exceed 255 characters"),
  description: z.string().optional(),
  instructions: z.string().optional(),
  scope: z.nativeEnum(ANNOTATION_QUEUE_SCOPE),
  comments_enabled: z.boolean(),
  feedback_definition_names: z.array(z.string()).default([]),
});

type FormData = z.infer<typeof formSchema>;

type AddEditAnnotationQueueDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  onQueueCreated?: (queue: Partial<AnnotationQueue>) => void;
  projectId?: string;
  scope?: ANNOTATION_QUEUE_SCOPE;
  queue?: AnnotationQueue;
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
    },
  });

  const { mutate: createMutate } = useAnnotationQueueCreateMutation();
  const { mutate: updateMutate } = useAnnotationQueueUpdateMutation();

  const isEdit = Boolean(defaultQueue);
  const title = isEdit
    ? "Edit annotation queue"
    : "Create a new annotation queue";
  const submitText = isEdit
    ? "Update annotation queue"
    : "Create annotation queue";

  const getQueue = useCallback(() => {
    const formData = form.getValues();
    return {
      ...formData,
      project_id: formData.project_id,
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
      { onSuccess: onQueueCreatedEdited },
    );
    setOpen(false);
  }, [createMutate, getQueue, onQueueCreatedEdited, setOpen]);

  const editQueue = useCallback(() => {
    updateMutate(
      {
        annotationQueue: {
          id: defaultQueue?.id || "",
          ...getQueue(),
        },
      },
      { onSuccess: onQueueCreatedEdited },
    );
    setOpen(false);
  }, [updateMutate, defaultQueue?.id, getQueue, onQueueCreatedEdited, setOpen]);

  const onSubmit = useCallback(
    () => (isEdit ? editQueue() : createQueue()),
    [isEdit, editQueue, createQueue],
  );

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent
        className="max-w-lg sm:max-w-[790px]"
        hideOverlay={isNestedDialogOpen}
      >
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <Form {...form}>
            <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
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
              <div className="flex gap-4">
                <FormField
                  control={form.control}
                  name="project_id"
                  render={({ field, formState }) => {
                    const validationErrors = get(formState.errors, [
                      "project_id",
                    ]);

                    return (
                      <FormItem className="flex-1">
                        <FormLabel>Project</FormLabel>
                        <FormControl>
                          <ProjectsSelectBox
                            value={field.value}
                            onValueChange={field.onChange}
                            className={cn({
                              "border-destructive": Boolean(
                                validationErrors?.message,
                              ),
                            })}
                            disabled={Boolean(projectId)}
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
                    <FormItem className="flex-1">
                      <FormLabel>
                        Scope{" "}
                        <ExplainerIcon
                          className="inline"
                          {...EXPLAINERS_MAP[
                            EXPLAINER_ID.how_to_choose_annotation_queue_type
                          ]}
                        />
                      </FormLabel>
                      <FormControl>
                        <SelectBox
                          placeholder="Trace"
                          value={field.value}
                          onChange={field.onChange}
                          options={SCOPE_OPTIONS}
                          disabled={isEdit || Boolean(scope)}
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>
              <Separator orientation="horizontal" className="my-4" />
              <div className="space-y-4">
                <div className="comet-body-s text-muted-slate">
                  Annotation guidelines
                </div>
                <Description>
                  Set how items are scored and labeled, and provide instructions
                  so annotators give consistent feedback.
                </Description>
              </div>
              <div className="space-y-4">
                <FormField
                  control={form.control}
                  name="instructions"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Instructions</FormLabel>
                      <FormControl>
                        <Textarea
                          placeholder="Instructions for annotators"
                          rows={4}
                          {...field}
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>
              <div className="space-y-4">
                <FormField
                  control={form.control}
                  name="feedback_definition_names"
                  render={({ field, formState }) => {
                    const validationErrors = get(formState.errors, [
                      "feedback_definition_names",
                    ]);

                    return (
                      <FormItem>
                        <FormLabel>
                          Available feedback scores (optional){" "}
                          <ExplainerIcon
                            className="inline"
                            {...EXPLAINERS_MAP[EXPLAINER_ID.visible_scores]}
                          />
                        </FormLabel>
                        <FormControl>
                          <FeedbackDefinitionsSelectBox
                            value={field.value}
                            onChange={field.onChange}
                            valueField="name"
                            multiselect
                            showSelectAll
                            onInnerDialogOpenChange={setIsNestedDialogOpen}
                            className={cn({
                              "border-destructive": Boolean(
                                validationErrors?.message,
                              ),
                            })}
                          />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    );
                  }}
                />
              </div>
              <div className="space-y-4">
                <div className="comet-body-s text-muted-slate">
                  Share annotation queue
                </div>
                <Description>
                  You must invite annotators to your workspace for them to
                  review the items. After creating the queue, you&apos;ll get a
                  direct link to share with them.
                </Description>
              </div>
            </form>
          </Form>
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button type="submit" onClick={form.handleSubmit(onSubmit)}>
            {submitText}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditAnnotationQueueDialog;

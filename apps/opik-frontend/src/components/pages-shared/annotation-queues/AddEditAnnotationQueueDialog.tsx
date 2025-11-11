import React, { useCallback, useMemo } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import get from "lodash/get";
import { useTranslation } from "react-i18next";

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

const useScopeOptions = () => {
  const { t } = useTranslation();
  
  return useMemo(() => [
    {
      value: ANNOTATION_QUEUE_SCOPE.TRACE,
      label: t("annotationQueues.scope.traces"),
    },
    {
      value: ANNOTATION_QUEUE_SCOPE.THREAD,
      label: t("annotationQueues.scope.threads"),
    },
  ], [t]);
};

const useFormSchema = () => {
  const { t } = useTranslation();
  
  return useMemo(() => z.object({
    project_id: z.string().min(1, t("annotationQueues.dialog.validation.projectRequired")),
    name: z
      .string()
      .min(1, t("annotationQueues.dialog.validation.nameRequired"))
      .max(255, t("annotationQueues.dialog.validation.nameMaxLength")),
    description: z.string().optional(),
    instructions: z.string().optional(),
    scope: z.nativeEnum(ANNOTATION_QUEUE_SCOPE),
    comments_enabled: z.boolean(),
    feedback_definition_names: z
      .array(z.string())
      .min(1, t("annotationQueues.dialog.validation.feedbackRequired")),
  }), [t]);
};

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
  const { t } = useTranslation();
  const formSchema = useFormSchema();
  const SCOPE_OPTIONS = useScopeOptions();
  
  type FormData = z.infer<typeof formSchema>;
  
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
    ? t("annotationQueues.dialog.editTitle")
    : t("annotationQueues.dialog.createTitle");
  const submitText = isEdit
    ? t("annotationQueues.dialog.updateButton")
    : t("annotationQueues.dialog.createButton");

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
      <DialogContent className="max-w-lg sm:max-w-[790px]">
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
                      <FormLabel>{t("annotationQueues.dialog.name")}</FormLabel>
                      <FormControl>
                        <Input
                          className={cn({
                            "border-destructive": Boolean(
                              validationErrors?.message,
                            ),
                          })}
                          placeholder={t("annotationQueues.dialog.namePlaceholder")}
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
                        <FormLabel>{t("annotationQueues.dialog.project")}</FormLabel>
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
                        {t("annotationQueues.dialog.scope")}{" "}
                        <ExplainerIcon
                          className="inline"
                          {...EXPLAINERS_MAP[
                            EXPLAINER_ID.how_to_choose_annotation_queue_type
                          ]}
                        />
                      </FormLabel>
                      <FormControl>
                        <SelectBox
                          placeholder={t("annotationQueues.scope.trace")}
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
                  {t("annotationQueues.dialog.annotationGuidelines")}
                </div>
                <Description>
                  {t("annotationQueues.dialog.annotationGuidelinesDesc")}
                </Description>
              </div>
              <div className="space-y-4">
                <FormField
                  control={form.control}
                  name="instructions"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>{t("annotationQueues.dialog.instructions")}</FormLabel>
                      <FormControl>
                        <Textarea
                          placeholder={t("annotationQueues.dialog.instructionsPlaceholder")}
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
                          {t("annotationQueues.dialog.availableFeedbackScores")}{" "}
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
                  {t("annotationQueues.dialog.shareQueue")}
                </div>
                <Description>
                  {t("annotationQueues.dialog.shareQueueDesc")}
                </Description>
              </div>
            </form>
          </Form>
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">{t("common.cancel")}</Button>
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

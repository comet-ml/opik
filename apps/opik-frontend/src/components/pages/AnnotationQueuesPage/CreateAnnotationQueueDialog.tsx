import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";

import { Button } from "@/components/ui/button";
import {
  Dialog,
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
import { Label } from "@/components/ui/label";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import FeedbackDefinitionSelector from "./FeedbackDefinitionSelector";

import useAppStore from "@/store/AppStore";
import {
  AnnotationQueue,
  AnnotationQueueScope,
} from "@/types/annotation-queues";
import useAnnotationQueueCreateMutation from "@/api/annotation-queues/useAnnotationQueueCreateMutation";
import useProjectsList from "@/api/projects/useProjectsList";
import { keepPreviousData } from "@tanstack/react-query";
import { useToast } from "@/components/ui/use-toast";
import { ToastAction } from "@/components/ui/toast";

const SCOPE_OPTIONS = [
  {
    value: AnnotationQueueScope.TRACE,
    label: "Traces",
    description: "Review individual traces and their spans",
  },
  {
    value: AnnotationQueueScope.THREAD,
    label: "Threads",
    description: "Review conversation threads and their messages",
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
  scope: z.nativeEnum(AnnotationQueueScope),
  feedback_definitions: z
    .array(z.string())
    .min(1, "At least one feedback definition is required"),
});

type FormData = z.infer<typeof formSchema>;

type CreateAnnotationQueueDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  onSetOpen?: (open: boolean) => void;
  onSuccess?: (queue: AnnotationQueue) => void;
  defaultScope?: AnnotationQueueScope;
  defaultProjectId?: string;
};

const CreateAnnotationQueueDialog: React.FunctionComponent<
  CreateAnnotationQueueDialogProps
> = ({
  open,
  setOpen,
  onSetOpen,
  onSuccess,
  defaultScope,
  defaultProjectId,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: projectsData } = useProjectsList(
    {
      workspaceName,
      page: 1,
      size: 1000, // Get all projects for selection
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const projects = useMemo(
    () => projectsData?.content ?? [],
    [projectsData?.content],
  );
  const projectOptions = useMemo(
    () =>
      projects.map((project) => ({
        value: project.id,
        label: project.name,
        description: project.description,
      })),
    [projects],
  );

  const form = useForm<FormData>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      project_id: defaultProjectId || "",
      name: "",
      description: "", // Always provide default empty string
      instructions: "",
      scope: defaultScope || AnnotationQueueScope.TRACE,
      feedback_definitions: [],
    },
  });

  const { mutate: createMutate, isPending } =
    useAnnotationQueueCreateMutation();
  const { toast } = useToast();

  // Update scope when defaultScope changes
  useEffect(() => {
    if (defaultScope && open) {
      form.setValue("scope", defaultScope);
    }
  }, [defaultScope, open, form]);

  // Update project_id when defaultProjectId changes
  useEffect(() => {
    if (defaultProjectId && open) {
      form.setValue("project_id", defaultProjectId);
    }
  }, [defaultProjectId, open, form]);

  const handleClose = useCallback(
    (open: boolean) => {
      if (onSetOpen) {
        onSetOpen(open);
      } else {
        setOpen(open);
      }
      if (!open) {
        form.reset();
      }
    },
    [form, onSetOpen, setOpen],
  );

  const onSubmit = useCallback(
    (data: FormData) => {
      createMutate(
        {
          annotationQueue: {
            project_id: data.project_id,
            name: data.name,
            description: data.description || "Default description",
            instructions: data.instructions || "Default instructions",
            scope: data.scope,
            comments_enabled: true,
            feedback_definitions: data.feedback_definitions,
          },
        },
        {
          onSuccess: (createdQueue) => {
            handleClose(false);

            // Show success toast with exact styling specifications
            toast({
              title: "Annotation queue created",
              description: (
                <div className="space-y-1">
                  <div>
                    You can now add traces to the annotation queue.{" "}
                    <a
                      href="#"
                      className="text-blue-600 underline hover:text-blue-800"
                      onClick={(e) => {
                        e.preventDefault();
                        // TODO: Navigate to workspace invite page
                      }}
                    >
                      Invite annotators to your workspace
                    </a>{" "}
                    and share this queue with them so they can start annotating
                    and provide feedback to improve the evaluation of your LLM
                    application.
                  </div>
                  <div>
                    <a
                      href="#"
                      className="text-sm text-blue-600 underline hover:text-blue-800"
                      onClick={(e) => {
                        e.preventDefault();
                        navigator.clipboard.writeText(window.location.href);
                      }}
                    >
                      Copy sharing link
                    </a>
                  </div>
                </div>
              ),
              className:
                "w-[468px] min-h-[120px] max-w-none flex flex-col items-start gap-1 self-stretch p-4 rounded-md border border-slate-200 bg-white shadow-lg",
              style: {
                boxShadow:
                  "0 4px 6px -1px rgba(0, 0, 0, 0.10), 0 2px 4px -2px rgba(0, 0, 0, 0.10)",
                position: "fixed",
                right: "16px",
                top: "auto",
                bottom: "16px",
                left: "auto",
              },
            });

            if (onSuccess) {
              onSuccess(createdQueue);
            }
          },
        },
      );
    },
    [createMutate, handleClose, onSuccess, toast],
  );

  const isValid = form.formState.isValid;

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="max-w-lg sm:max-w-screen-sm">
        <DialogHeader>
          <DialogTitle>Create a new annotation queue</DialogTitle>
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
            <FormField
              control={form.control}
              name="name"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Name</FormLabel>
                  <FormControl>
                    <Input placeholder="Annotation queue name" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <div className="flex gap-4">
              <FormField
                control={form.control}
                name="project_id"
                render={({ field }) => (
                  <FormItem className="flex-1">
                    <FormLabel>Project</FormLabel>
                    <FormControl>
                      <SelectBox
                        placeholder="Default project"
                        value={field.value}
                        onChange={field.onChange}
                        options={projectOptions}
                        disabled={!!defaultProjectId}
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="scope"
                render={({ field }) => (
                  <FormItem className="flex-1">
                    <FormLabel>Scope</FormLabel>
                    <FormControl>
                      <SelectBox
                        placeholder="Trace"
                        value={field.value}
                        onChange={field.onChange}
                        options={SCOPE_OPTIONS}
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </div>

            <div className="space-y-4">
              <div>
                <h3 className="text-sm font-medium text-foreground">
                  Annotation guidelines
                </h3>
                <p className="text-sm text-muted-foreground">
                  Set how items are scored and labeled, and provide instructions
                  so annotators give consistent feedback.
                </p>
              </div>

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

              <FormField
                control={form.control}
                name="feedback_definitions"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Available feedback scores</FormLabel>
                    <FormControl>
                      <FeedbackDefinitionSelector
                        value={field.value}
                        onChange={field.onChange}
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </div>

            <div className="space-y-4">
              <div>
                <h3 className="text-sm font-medium text-foreground">
                  Share annotation queue
                </h3>
                <p className="text-sm text-muted-foreground">
                  You must{" "}
                  <a
                    href="#"
                    className="text-blue-600 underline hover:text-blue-800"
                    onClick={(e) => {
                      e.preventDefault();
                      // TODO: Navigate to workspace invite page
                    }}
                  >
                    invite annotators to your workspace
                  </a>{" "}
                  for them to review the items. After creating the queue, you'll
                  get a direct link to share with them.
                </p>
              </div>
            </div>

            <DialogFooter>
              <Button
                variant="outline"
                type="button"
                onClick={() => handleClose(false)}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={!isValid || isPending}>
                {isPending ? "Creating..." : "Create annotation queue"}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
};

export default CreateAnnotationQueueDialog;

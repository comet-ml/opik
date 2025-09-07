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
import { AnnotationQueue, AnnotationQueueScope } from "@/types/annotation-queues";
import useAnnotationQueueCreateMutation from "@/api/annotation-queues/useAnnotationQueueCreateMutation";
import useProjectsList from "@/api/projects/useProjectsList";
import { keepPreviousData } from "@tanstack/react-query";

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
  name: z.string().min(1, "Name is required").max(255, "Name cannot exceed 255 characters"),
  description: z.string().optional(),
  instructions: z.string().optional(),
  scope: z.nativeEnum(AnnotationQueueScope),
  comments_enabled: z.boolean(),
  feedback_definitions: z.array(z.string()).min(1, "At least one feedback definition is required"),
});

type FormData = z.infer<typeof formSchema>;

type CreateAnnotationQueueDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  onSetOpen?: (open: boolean) => void;
  onSuccess?: (queue: AnnotationQueue) => void;
  defaultScope?: AnnotationQueueScope;
};

const CreateAnnotationQueueDialog: React.FunctionComponent<CreateAnnotationQueueDialogProps> = ({
  open,
  setOpen,
  onSetOpen,
  onSuccess,
  defaultScope,
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

  const projects = useMemo(() => projectsData?.content ?? [], [projectsData?.content]);
  const projectOptions = useMemo(() => 
    projects.map(project => ({
      value: project.id,
      label: project.name,
      description: project.description,
    })), 
    [projects]
  );

  const form = useForm<FormData>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      project_id: "",
      name: "",
      description: "",
      instructions: "",
      scope: defaultScope || AnnotationQueueScope.TRACE,
      comments_enabled: true,
      feedback_definitions: [],
    },
  });

  const { mutate: createMutate, isPending } = useAnnotationQueueCreateMutation();

  // Update scope when defaultScope changes
  useEffect(() => {
    if (defaultScope && open) {
      form.setValue("scope", defaultScope);
    }
  }, [defaultScope, open, form]);

  const handleClose = useCallback((open: boolean) => {
    if (onSetOpen) {
      onSetOpen(open);
    } else {
      setOpen(open);
    }
    if (!open) {
      form.reset();
    }
  }, [form, onSetOpen, setOpen]);

  const onSubmit = useCallback((data: FormData) => {
    createMutate(
      {
        annotationQueue: {
          project_id: data.project_id,
          name: data.name,
          description: data.description || undefined,
          instructions: data.instructions || undefined,
          scope: data.scope,
          comments_enabled: data.comments_enabled,
          feedback_definitions: data.feedback_definitions,
        },
      },
      {
        onSuccess: (createdQueue) => {
          handleClose(false);
          if (onSuccess) {
            onSuccess(createdQueue);
          }
        },
      },
    );
  }, [createMutate, handleClose, onSuccess]);

  const isValid = form.formState.isValid;

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="max-w-lg sm:max-w-[640px]">
        <DialogHeader>
          <DialogTitle>Create annotation queue</DialogTitle>
        </DialogHeader>
        
        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
            <FormField
              control={form.control}
              name="project_id"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Project</FormLabel>
                  <FormControl>
                    <SelectBox
                      placeholder="Select project"
                      value={field.value}
                      onChange={field.onChange}
                      options={projectOptions}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="name"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Name</FormLabel>
                  <FormControl>
                    <Input placeholder="Queue name" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="description"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Description</FormLabel>
                  <FormControl>
                    <Textarea 
                      placeholder="Brief description of the annotation queue"
                      rows={3}
                      {...field} 
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
                <FormItem>
                  <FormLabel>Scope</FormLabel>
                  <FormControl>
                    <SelectBox
                      placeholder="Select scope"
                      value={field.value}
                      onChange={field.onChange}
                      options={SCOPE_OPTIONS}
                    />
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
                  <FormLabel>Instructions</FormLabel>
                  <FormControl>
                    <Textarea 
                      placeholder="Instructions for reviewers on how to annotate items"
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
                  <FormLabel>Feedback Definitions</FormLabel>
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

            <div className="flex items-center space-x-2">
              <FormField
                control={form.control}
                name="comments_enabled"
                render={({ field }) => (
                  <FormItem className="flex flex-row items-start space-x-3 space-y-0">
                    <FormControl>
                      <input
                        type="checkbox"
                        checked={field.value}
                        onChange={(e) => field.onChange(e.target.checked)}
                        className="mt-1"
                      />
                    </FormControl>
                    <div className="space-y-1 leading-none">
                      <FormLabel>Enable comments</FormLabel>
                      <p className="text-sm text-muted-foreground">
                        Allow reviewers to add text comments when annotating
                      </p>
                    </div>
                  </FormItem>
                )}
              />
            </div>

            <DialogFooter>
              <DialogClose asChild>
                <Button variant="outline" type="button">
                  Cancel
                </Button>
              </DialogClose>
              <Button 
                type="submit" 
                disabled={!isValid || isPending}
              >
                {isPending ? "Creating..." : "Create queue"}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
};

export default CreateAnnotationQueueDialog;

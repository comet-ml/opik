import React from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import * as z from "zod";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
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
import useAppStore from "@/store/AppStore";
import useAnnotationQueueCreateMutation from "@/api/annotationQueues/useAnnotationQueueCreateMutation";

const formSchema = z.object({
  name: z.string().min(1, "Name is required").max(255, "Name is too long"),
  description: z.string().optional(),
  instructions: z.string().optional(),
});

type FormValues = z.infer<typeof formSchema>;

interface CreateAnnotationQueueDialogProps {
  open: boolean;
  setOpen: (open: boolean) => void;
}

const CreateAnnotationQueueDialog: React.FunctionComponent<
  CreateAnnotationQueueDialogProps
> = ({ open, setOpen }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const createAnnotationQueueMutation = useAnnotationQueueCreateMutation();

  const form = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      name: "",
      description: "",
      instructions: "",
    },
  });

  const handleSubmit = (values: FormValues) => {
    createAnnotationQueueMutation.mutate(
      {
        workspaceName,
        queue: {
          name: values.name,
          description: values.description || undefined,
          instructions: values.instructions || undefined,
          visible_fields: ["input", "output", "timestamp"],
          required_metrics: ["rating"],
          optional_metrics: ["comment"],
        },
      },
      {
        onSuccess: () => {
          setOpen(false);
          form.reset();
        },
      },
    );
  };

  const handleClose = () => {
    setOpen(false);
    form.reset();
  };

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>Create Annotation Queue</DialogTitle>
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(handleSubmit)} className="space-y-4">
            <FormField
              control={form.control}
              name="name"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Name *</FormLabel>
                  <FormControl>
                    <Input
                      placeholder="Customer Support Q4 Review"
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
                <FormItem>
                  <FormLabel>Description</FormLabel>
                  <FormControl>
                    <Textarea
                      placeholder="Review customer support agent responses for Q4"
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
              name="instructions"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Instructions for SMEs</FormLabel>
                  <FormControl>
                    <Textarea
                      placeholder="Please rate each response on a scale of 1-5 and provide feedback..."
                      rows={3}
                      {...field}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <div className="flex justify-end gap-3 pt-4">
              <Button type="button" variant="outline" onClick={handleClose}>
                Cancel
              </Button>
              <Button
                type="submit"
                loading={createAnnotationQueueMutation.isPending}
              >
                Create Queue
              </Button>
            </div>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
};

export default CreateAnnotationQueueDialog;
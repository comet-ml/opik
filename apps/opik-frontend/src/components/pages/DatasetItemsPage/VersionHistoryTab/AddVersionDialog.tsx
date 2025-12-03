import React, { useCallback } from "react";
import { useForm, Controller } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Blocks, Code2, Loader2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
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
} from "@/components/ui/form";
import { Textarea } from "@/components/ui/textarea";
import { useToast } from "@/components/ui/use-toast";
import { ToastAction } from "@/components/ui/toast";
import TagListRenderer from "@/components/shared/TagListRenderer/TagListRenderer";
import useCommitDatasetVersionMutation from "@/api/datasets/useCommitDatasetVersionMutation";
import { useNavigateToExperiment } from "@/hooks/useNavigateToExperiment";
import useLoadPlayground from "@/hooks/useLoadPlayground";

const addVersionSchema = z.object({
  versionNote: z.string().optional(),
  tags: z.array(z.string()),
});

type AddVersionFormData = z.infer<typeof addVersionSchema>;

type AddVersionDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  datasetId: string;
  datasetName?: string;
};

const AddVersionDialog: React.FunctionComponent<AddVersionDialogProps> = ({
  open,
  setOpen,
  datasetId,
  datasetName,
}) => {
  const commitVersionMutation = useCommitDatasetVersionMutation();
  const { toast } = useToast();
  const { navigate: navigateToExperiment } = useNavigateToExperiment();
  const { loadPlayground } = useLoadPlayground();

  const form = useForm<AddVersionFormData>({
    resolver: zodResolver(addVersionSchema),
    defaultValues: {
      versionNote: "",
      tags: ["Latest"],
    },
  });

  const showSuccessToast = useCallback(() => {
    toast({
      title: "New version created",
      description:
        "Your dataset changes have been saved as a new version. You can now use it to run experiments in the SDK or the Playground.",
      actions: [
        <ToastAction
          variant="link"
          size="sm"
          className="comet-body-s-accented gap-1.5 px-0"
          altText="Run experiment in the SDK"
          key="sdk"
          onClick={() =>
            navigateToExperiment({
              newExperiment: true,
              datasetName,
            })
          }
        >
          <Code2 className="size-4" />
          Run experiment in the SDK
        </ToastAction>,
        <ToastAction
          variant="link"
          size="sm"
          className="comet-body-s-accented gap-1.5 px-0"
          altText="Run experiment in the Playground"
          key="playground"
          onClick={() => loadPlayground({ datasetId })}
        >
          <Blocks className="size-4" />
          Run experiment in the Playground
        </ToastAction>,
      ],
    });
  }, [toast, navigateToExperiment, datasetName, loadPlayground, datasetId]);

  const onSubmit = (data: AddVersionFormData) => {
    commitVersionMutation.mutate(
      {
        datasetId,
        changeDescription: data.versionNote,
        tags: data.tags,
      },
      {
        onSuccess: () => {
          showSuccessToast();
          form.reset();
          setOpen(false);
        },
      },
    );
  };

  const handleCancel = () => {
    form.reset();
    setOpen(false);
  };

  const handleOpenChange = (newOpen: boolean) => {
    if (commitVersionMutation.isPending) return;
    setOpen(newOpen);
    if (!newOpen) {
      form.reset();
    }
  };

  const isSubmitting = commitVersionMutation.isPending;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Save changes</DialogTitle>
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
            <p className="text-sm text-muted-foreground">
              Saving your changes will create a new version. You&apos;ll be able
              to use it in experiments or in the Playground. The previous
              version will remain available in version history.
            </p>

            <FormField
              control={form.control}
              name="versionNote"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Version note (optional)</FormLabel>
                  <FormControl>
                    <Textarea
                      {...field}
                      placeholder="Describe what changed in this version"
                      className="min-h-32 resize-none"
                      disabled={isSubmitting}
                    />
                  </FormControl>
                </FormItem>
              )}
            />

            <Controller
              control={form.control}
              name="tags"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Tags</FormLabel>
                  <FormControl>
                    <TagListRenderer
                      tags={field.value}
                      onAddTag={(newTag) =>
                        form.setValue("tags", [...field.value, newTag])
                      }
                      onDeleteTag={(tagToDelete) =>
                        form.setValue(
                          "tags",
                          field.value.filter((tag) => tag !== tagToDelete),
                        )
                      }
                      align="start"
                    />
                  </FormControl>
                </FormItem>
              )}
            />

            <DialogFooter className="gap-3 border-t pt-6 md:gap-0">
              <Button
                type="button"
                variant="outline"
                onClick={handleCancel}
                disabled={isSubmitting}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={isSubmitting}>
                {isSubmitting && (
                  <Loader2 className="mr-2 size-4 animate-spin" />
                )}
                Save changes
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
};

export default AddVersionDialog;

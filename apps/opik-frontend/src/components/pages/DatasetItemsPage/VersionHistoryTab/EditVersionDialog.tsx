import React from "react";
import { useForm, Controller } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";

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
import TagListRenderer from "@/components/shared/TagListRenderer/TagListRenderer";
import { DatasetVersion } from "@/types/datasets";

const editVersionSchema = z.object({
  versionNote: z.string().optional(),
  tags: z.array(z.string()),
});

type EditVersionFormData = z.infer<typeof editVersionSchema>;

type EditVersionDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  version: DatasetVersion;
};

const EditVersionDialog: React.FunctionComponent<EditVersionDialogProps> = ({
  open,
  setOpen,
  version,
}) => {
  const form = useForm<EditVersionFormData>({
    resolver: zodResolver(editVersionSchema),
    defaultValues: {
      versionNote: version.change_description || "",
      tags: version.tags || [],
    },
  });

  const onSubmit = (data: EditVersionFormData) => {
    console.log("Edit version form data:", {
      versionId: version.id,
      versionHash: version.version_hash,
      ...data,
    });
    setOpen(false);
  };

  const handleCancel = () => {
    form.reset();
    setOpen(false);
  };

  const handleOpenChange = (newOpen: boolean) => {
    setOpen(newOpen);
    if (!newOpen) {
      form.reset();
    }
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Edit version</DialogTitle>
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
            <p className="text-sm text-muted-foreground">
              Edit the version note and tags to keep your dataset versions
              organized.
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
                      placeholder="Refined tags and category mappings."
                      className="min-h-32 resize-none"
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
              <Button type="button" variant="outline" onClick={handleCancel}>
                Cancel
              </Button>
              <Button type="submit">Update version</Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
};

export default EditVersionDialog;

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

const addVersionSchema = z.object({
  versionNote: z.string().optional(),
  tags: z.array(z.string()),
});

type AddVersionFormData = z.infer<typeof addVersionSchema>;

type AddVersionDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  datasetId: string;
};

const AddVersionDialog: React.FunctionComponent<AddVersionDialogProps> = ({
  open,
  setOpen,
  datasetId,
}) => {
  const form = useForm<AddVersionFormData>({
    resolver: zodResolver(addVersionSchema),
    defaultValues: {
      versionNote: "",
      tags: ["Latest"],
    },
  });

  const onSubmit = (data: AddVersionFormData) => {
    console.log("Add version form data:", {
      datasetId,
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
              <Button type="submit">Save changes</Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
};

export default AddVersionDialog;

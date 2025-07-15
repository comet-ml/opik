import React, { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import * as z from "zod";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import { useToast } from "@/components/ui/use-toast";
import useTagCreate from "@/api/tags/useTagCreate";
import useTagUpdate from "@/api/tags/useTagUpdate";
import { Tag, TagCreate, TagUpdate } from "@/types/tags";

const tagSchema = z.object({
  name: z.string().min(1, "Name is required").max(100, "Name must be less than 100 characters"),
  description: z.string().max(500, "Description must be less than 500 characters").optional(),
});

type TagFormData = z.infer<typeof tagSchema>;

interface AddEditTagDialogProps {
  open: boolean;
  onClose: () => void;
  tag?: Tag | null;
  onSuccess?: () => void;
}

const AddEditTagDialog: React.FunctionComponent<AddEditTagDialogProps> = ({
  open,
  onClose,
  tag,
  onSuccess,
}) => {
  const { toast } = useToast();
  const createTagMutation = useTagCreate();
  const updateTagMutation = useTagUpdate();
  const isEditing = Boolean(tag);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<TagFormData>({
    resolver: zodResolver(tagSchema),
    defaultValues: {
      name: "",
      description: "",
    },
  });

  useEffect(() => {
    if (open) {
      reset({
        name: tag?.name || "",
        description: tag?.description || "",
      });
    }
  }, [open, tag, reset]);

  const onSubmit = async (data: TagFormData) => {
    try {
      if (isEditing && tag) {
        const tagUpdate: TagUpdate = {
          name: data.name,
          description: data.description,
        };
        await updateTagMutation.mutateAsync({ id: tag.id, tagUpdate });
        toast({
          title: "Success",
          description: "Tag updated successfully",
        });
      } else {
        const tagCreate: TagCreate = {
          name: data.name,
          description: data.description,
        };
        await createTagMutation.mutateAsync(tagCreate);
        toast({
          title: "Success",
          description: "Tag created successfully",
        });
      }

      if (onSuccess) {
        onSuccess();
      }
      onClose();
    } catch (error: any) {
      toast({
        title: "Error",
        description: error?.response?.data?.message || "Failed to save tag",
        variant: "destructive",
      });
    }
  };

  const handleClose = () => {
    if (!isSubmitting) {
      onClose();
    }
  };

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="sm:max-w-[425px]">
        <DialogHeader>
          <DialogTitle>
            {isEditing ? "Edit Tag" : "Create New Tag"}
          </DialogTitle>
        </DialogHeader>
        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="name">Name *</Label>
            <Input
              id="name"
              placeholder="Enter tag name"
              {...register("name")}
              disabled={isSubmitting}
            />
            {errors.name && (
              <p className="text-sm text-red-500">{errors.name.message}</p>
            )}
          </div>
          <div className="space-y-2">
            <Label htmlFor="description">Description</Label>
            <Textarea
              id="description"
              placeholder="Enter tag description (optional)"
              {...register("description")}
              disabled={isSubmitting}
              rows={3}
            />
            {errors.description && (
              <p className="text-sm text-red-500">{errors.description.message}</p>
            )}
          </div>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={handleClose}
              disabled={isSubmitting}
            >
              Cancel
            </Button>
            <Button type="submit" disabled={isSubmitting}>
              {isSubmitting ? "Saving..." : isEditing ? "Update Tag" : "Create Tag"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditTagDialog;
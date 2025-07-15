import React, { useState } from "react";
import { Button } from "@/components/ui/button";
import { Trash2 } from "lucide-react";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/dialog";
import { useToast } from "@/components/ui/use-toast";
import useTagDelete from "@/api/tags/useTagDelete";
import { Tag } from "@/types/tags";

interface TagsActionsPanelProps {
  selectedRows: Tag[];
  onSuccess?: () => void;
}

const TagsActionsPanel: React.FunctionComponent<TagsActionsPanelProps> = ({
  selectedRows,
  onSuccess,
}) => {
  const [isDeleteDialogOpen, setIsDeleteDialogOpen] = useState(false);
  const { toast } = useToast();
  const deleteTagMutation = useTagDelete();

  const handleBulkDelete = async () => {
    try {
      const deletePromises = selectedRows.map((tag) =>
        deleteTagMutation.mutateAsync(tag.id)
      );
      await Promise.all(deletePromises);
      
      toast({
        title: "Success",
        description: `Deleted ${selectedRows.length} tag${selectedRows.length > 1 ? 's' : ''} successfully`,
      });
      
      setIsDeleteDialogOpen(false);
      if (onSuccess) {
        onSuccess();
      }
    } catch (error: any) {
      toast({
        title: "Error",
        description: "Failed to delete some tags. Please try again.",
        variant: "destructive",
      });
    }
  };

  if (selectedRows.length === 0) {
    return null;
  }

  return (
    <div className="flex items-center justify-between rounded-lg border border-border bg-muted/50 px-4 py-3">
      <div className="flex items-center gap-2">
        <span className="text-sm font-medium">
          {selectedRows.length} tag{selectedRows.length > 1 ? 's' : ''} selected
        </span>
      </div>
      <div className="flex items-center gap-2">
        <Button
          variant="outline"
          size="sm"
          onClick={() => setIsDeleteDialogOpen(true)}
          className="text-red-600 hover:text-red-700"
        >
          <Trash2 className="mr-2 h-4 w-4" />
          Delete Selected
        </Button>
      </div>

      <AlertDialog open={isDeleteDialogOpen} onOpenChange={setIsDeleteDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete Selected Tags</AlertDialogTitle>
            <AlertDialogDescription>
              Are you sure you want to delete {selectedRows.length} selected tag{selectedRows.length > 1 ? 's' : ''}? This action cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleBulkDelete}
              className="bg-red-600 hover:bg-red-700"
              disabled={deleteTagMutation.isPending}
            >
              {deleteTagMutation.isPending ? "Deleting..." : "Delete Selected"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
};

export default TagsActionsPanel;
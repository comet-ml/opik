import React, { useCallback, useState } from "react";
import { Check, X } from "lucide-react";

import { Button } from "@/components/ui/button";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { useToast } from "@/components/ui/use-toast";

interface DashboardSaveActionsProps {
  hasUnsavedChanges: boolean;
  onSave: () => Promise<void>;
  onDiscard: () => void;
}

const DashboardSaveActions: React.FunctionComponent<
  DashboardSaveActionsProps
> = ({ hasUnsavedChanges, onSave, onDiscard }) => {
  const [showDiscardDialog, setShowDiscardDialog] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const { toast } = useToast();

  const handleSave = useCallback(async () => {
    setIsSaving(true);
    try {
      await onSave();
      toast({
        title: "Dashboard saved",
        description: "Your changes have been saved successfully.",
      });
    } catch (error) {
      toast({
        title: "Failed to save dashboard",
        description: "Please try again.",
        variant: "destructive",
      });
    } finally {
      setIsSaving(false);
    }
  }, [onSave, toast]);

  const handleDiscardClick = useCallback(() => {
    setShowDiscardDialog(true);
  }, []);

  const handleConfirmDiscard = useCallback(() => {
    onDiscard();
    setShowDiscardDialog(false);
    toast({
      title: "Changes discarded",
      description: "Your changes have been discarded.",
    });
  }, [onDiscard, toast]);

  if (!hasUnsavedChanges) {
    return null;
  }

  return (
    <>
      <Button
        variant="outline"
        size="sm"
        onClick={handleDiscardClick}
        disabled={isSaving}
      >
        <X className="mr-2 size-4" />
        Discard changes
      </Button>
      <Button
        variant="default"
        size="sm"
        onClick={handleSave}
        disabled={isSaving}
      >
        <Check className="mr-2 size-4" />
        Save changes
      </Button>

      <ConfirmDialog
        open={showDiscardDialog}
        setOpen={setShowDiscardDialog}
        onConfirm={handleConfirmDiscard}
        title="Discard changes?"
        description="All unsaved changes will be removed. This will return the dashboard to its last saved version."
        confirmText="Discard changes"
        cancelText="Cancel"
        confirmButtonVariant="destructive"
      />
    </>
  );
};

export default DashboardSaveActions;

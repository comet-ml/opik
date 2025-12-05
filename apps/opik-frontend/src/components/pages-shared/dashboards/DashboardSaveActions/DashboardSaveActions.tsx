import React, { useCallback, useState, useMemo } from "react";
import { Check, X } from "lucide-react";

import { Button } from "@/components/ui/button";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { useToast } from "@/components/ui/use-toast";
import AddEditCloneDashboardDialog from "@/components/pages-shared/dashboards/AddEditCloneDashboardDialog/AddEditCloneDashboardDialog";
import { useDashboardStore } from "@/store/DashboardStore";
import { Dashboard } from "@/types/dashboard";

interface DashboardSaveActionsProps {
  hasUnsavedChanges: boolean;
  onSave: () => Promise<void>;
  onDiscard: () => void;
  dashboard: Dashboard;
}

const DashboardSaveActions: React.FunctionComponent<
  DashboardSaveActionsProps
> = ({ hasUnsavedChanges, onSave, onDiscard, dashboard }) => {
  const [showDiscardDialog, setShowDiscardDialog] = useState(false);
  const [saveAsDialogOpen, setSaveAsDialogOpen] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const { toast } = useToast();

  const getDashboardConfig = useDashboardStore(
    (state) => state.getDashboardConfig,
  );

  const dashboardWithCurrentConfig = useMemo(
    () => ({
      ...dashboard,
      config: getDashboardConfig(),
    }),
    [dashboard, getDashboardConfig],
  );

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

  const handleSaveAsClick = useCallback(() => {
    setSaveAsDialogOpen(true);
  }, []);

  if (!hasUnsavedChanges) {
    return (
      <>
        <Button variant="outline" size="sm" onClick={handleSaveAsClick}>
          Save as...
        </Button>

        <AddEditCloneDashboardDialog
          mode="save_as"
          open={saveAsDialogOpen}
          setOpen={setSaveAsDialogOpen}
          dashboard={dashboardWithCurrentConfig}
        />
      </>
    );
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
        Save
      </Button>
      <Button
        variant="outline"
        size="sm"
        onClick={handleSaveAsClick}
        disabled={isSaving}
      >
        Save as...
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

      <AddEditCloneDashboardDialog
        mode="save_as"
        open={saveAsDialogOpen}
        setOpen={setSaveAsDialogOpen}
        dashboard={dashboardWithCurrentConfig}
      />
    </>
  );
};

export default DashboardSaveActions;

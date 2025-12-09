import React, { useCallback, useState, useRef } from "react";
import { Copy } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  ButtonWithDropdown,
  ButtonWithDropdownTrigger,
  ButtonWithDropdownContent,
  ButtonWithDropdownItem,
} from "@/components/ui/button-with-dropdown";
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
  const resetKeyRef = useRef(0);
  const [showDiscardDialog, setShowDiscardDialog] = useState(false);
  const [saveAsDialogOpen, setSaveAsDialogOpen] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const { toast } = useToast();

  const getDashboard = useDashboardStore((state) => state.getDashboard);

  const dashboardWithCurrentConfigRef = useRef(dashboard);

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
    // Get fresh config when opening the dialog
    dashboardWithCurrentConfigRef.current = {
      ...dashboard,
      config: getDashboard(),
    };
    setSaveAsDialogOpen(true);
    resetKeyRef.current = resetKeyRef.current + 1;
  }, [dashboard, getDashboard]);

  if (!hasUnsavedChanges) {
    return null;
  }

  return (
    <>
      <Button
        variant="destructive"
        size="sm"
        onClick={handleDiscardClick}
        disabled={isSaving}
      >
        Discard changes
      </Button>

      <ButtonWithDropdown>
        <ButtonWithDropdownTrigger
          variant="default"
          size="sm"
          onPrimaryClick={handleSave}
          disabled={isSaving}
        >
          Save changes
        </ButtonWithDropdownTrigger>
        <ButtonWithDropdownContent align="end">
          <ButtonWithDropdownItem onClick={handleSaveAsClick}>
            <Copy className="mr-2 size-4" />
            Save as new
          </ButtonWithDropdownItem>
        </ButtonWithDropdownContent>
      </ButtonWithDropdown>

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
        key={`save-as-${resetKeyRef.current}`}
        mode="save_as"
        open={saveAsDialogOpen}
        setOpen={setSaveAsDialogOpen}
        dashboard={dashboardWithCurrentConfigRef.current}
        onSuccess={onDiscard}
      />
    </>
  );
};

export default DashboardSaveActions;

import React, { useCallback, useState, useRef } from "react";
import { Copy } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  ButtonWithDropdown,
  ButtonWithDropdownTrigger,
  ButtonWithDropdownContent,
  ButtonWithDropdownItem,
} from "@/components/ui/button-with-dropdown";
import { Separator } from "@/components/ui/separator";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { useToast } from "@/components/ui/use-toast";
import useNavigationBlocker from "@/hooks/useNavigationBlocker";
import AddEditCloneDashboardDialog from "@/components/pages-shared/dashboards/AddEditCloneDashboardDialog/AddEditCloneDashboardDialog";
import {
  useDashboardStore,
  selectHasUnsavedChanges,
  selectMixedConfig,
} from "@/store/DashboardStore";
import { Dashboard } from "@/types/dashboard";

interface DashboardSaveActionsProps {
  onSave: () => Promise<void>;
  onDiscard: () => void;
  dashboard: Dashboard;
  isTemplate?: boolean;
  navigateOnCreate?: boolean;
  onDashboardCreated?: (dashboardId: string) => void;
}

const DashboardSaveActions: React.FunctionComponent<
  DashboardSaveActionsProps
> = ({
  onSave,
  onDiscard,
  dashboard,
  isTemplate = false,
  navigateOnCreate = true,
  onDashboardCreated,
}) => {
  const { toast } = useToast();
  const hasUnsavedChanges = useDashboardStore(selectHasUnsavedChanges);
  const getDashboard = useDashboardStore((state) => state.getDashboard);
  const mixedConfig = useDashboardStore(selectMixedConfig);

  const [isSaving, setIsSaving] = useState(false);
  const [discardDialogOpen, setDiscardDialogOpen] = useState(false);
  const [saveAsDialogOpen, setSaveAsDialogOpen] = useState(false);

  const saveAsDialogKey = useRef(0);
  const saveAsDialogDashboard = useRef(dashboard);
  const proceedNavigationRef = useRef<(() => void) | null>(null);
  const cancelNavigationRef = useRef<(() => void) | null>(null);

  const handleSave = useCallback(async () => {
    setIsSaving(true);
    try {
      await onSave();
      toast({
        title: "Dashboard saved",
        description: "Your changes have been saved successfully.",
      });
    } catch {
      toast({
        title: "Failed to save dashboard",
        description: "Please try again.",
        variant: "destructive",
      });
    } finally {
      setIsSaving(false);
    }
  }, [onSave, toast]);

  const handleDiscard = useCallback(() => {
    onDiscard();
    setDiscardDialogOpen(false);
    toast({
      title: "Changes discarded",
      description: "Your changes have been discarded.",
    });
  }, [onDiscard, toast]);

  const openSaveAsDialog = useCallback(() => {
    saveAsDialogDashboard.current = { ...dashboard, config: getDashboard() };
    saveAsDialogKey.current += 1;
    setSaveAsDialogOpen(true);
  }, [dashboard, getDashboard]);

  const handleSaveAsDialogClose = useCallback((open: boolean) => {
    if (!open) {
      cancelNavigationRef.current?.();
      proceedNavigationRef.current = null;
      cancelNavigationRef.current = null;
    }
    setSaveAsDialogOpen(open);
  }, []);

  const handleSaveAsSuccess = useCallback(
    (dashboardId: string) => {
      setSaveAsDialogOpen(false);
      onDiscard();
      onDashboardCreated?.(dashboardId);
      proceedNavigationRef.current?.();
      proceedNavigationRef.current = null;
      cancelNavigationRef.current = null;
    },
    [onDiscard, onDashboardCreated],
  );

  const handleSaveAndLeave = useCallback(
    (proceed: () => void, cancel: () => void) => {
      if (isTemplate) {
        proceedNavigationRef.current = proceed;
        cancelNavigationRef.current = cancel;
        openSaveAsDialog();
      } else {
        onSave().then(proceed).catch(cancel);
      }
    },
    [isTemplate, openSaveAsDialog, onSave],
  );

  const { DialogComponent: NavigationBlockerDialog } = useNavigationBlocker({
    condition: hasUnsavedChanges,
    title: "You have unsaved changes",
    description: isTemplate
      ? "You're viewing a template. Save as a new dashboard to keep your changes."
      : "If you leave now, your changes will be lost. Are you sure you want to continue?",
    confirmText: "Discard and leave",
    cancelText: "Stay on page",
    onSaveAndLeave: handleSaveAndLeave,
    saveAndLeaveText: isTemplate ? "Save as new dashboard" : "Save and leave",
  });

  if (!hasUnsavedChanges) {
    return null;
  }

  return (
    <>
      <Button
        variant="destructive"
        size="sm"
        onClick={() => setDiscardDialogOpen(true)}
        disabled={isSaving}
      >
        Discard changes
      </Button>

      {isTemplate ? (
        <Button size="sm" onClick={openSaveAsDialog} disabled={isSaving}>
          Save as new dashboard
        </Button>
      ) : (
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
            <ButtonWithDropdownItem onClick={openSaveAsDialog}>
              <Copy className="mr-2 size-4" />
              Save as new
            </ButtonWithDropdownItem>
          </ButtonWithDropdownContent>
        </ButtonWithDropdown>
      )}

      <Separator orientation="vertical" className="mx-2 h-4" />

      <ConfirmDialog
        open={discardDialogOpen}
        setOpen={setDiscardDialogOpen}
        onConfirm={handleDiscard}
        title="Discard changes?"
        description={
          isTemplate
            ? "All unsaved changes will be removed. This will return the template to its original state."
            : "All unsaved changes will be removed. This will return the dashboard to its last saved version."
        }
        confirmText="Discard changes"
        cancelText="Cancel"
        confirmButtonVariant="destructive"
      />

      <AddEditCloneDashboardDialog
        key={`save-as-${saveAsDialogKey.current}`}
        mode="save_as"
        open={saveAsDialogOpen}
        setOpen={handleSaveAsDialogClose}
        dashboard={saveAsDialogDashboard.current}
        onCreateSuccess={handleSaveAsSuccess}
        navigateOnCreate={navigateOnCreate}
        defaultProjectId={mixedConfig?.projectIds?.[0]}
        defaultExperimentIds={mixedConfig?.experimentIds?.slice()}
        defaultExperimentDataSource={mixedConfig?.experimentDataSource}
      />

      {NavigationBlockerDialog}
    </>
  );
};

export default DashboardSaveActions;

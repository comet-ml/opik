import { useCallback, useEffect, useState } from "react";
import { useBlocker } from "@tanstack/react-router";
import { Loader2 } from "lucide-react";
import isFunction from "lodash/isFunction";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogClose,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";

export interface UseNavigationBlockerOptions {
  condition: boolean;
  title?: string;
  description?: string;
  confirmText?: string;
  cancelText?: string;
  onSaveAndLeave?: () => Promise<void>;
  saveAndLeaveText?: string;
}

export interface UseNavigationBlockerResult {
  DialogComponent: JSX.Element;
}

const useNavigationBlocker = ({
  condition,
  title = "Unsaved changes",
  description = "Are you sure you want to leave?",
  confirmText = "Leave",
  cancelText = "Stay",
  onSaveAndLeave,
  saveAndLeaveText = "Save and leave",
}: UseNavigationBlockerOptions): UseNavigationBlockerResult => {
  const [showDialog, setShowDialog] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const { toast } = useToast();

  const { proceed, reset, status } = useBlocker({
    condition,
  });

  useEffect(() => {
    if (status === "blocked") {
      setShowDialog(true);
    }
  }, [status]);

  useEffect(() => {
    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      if (condition) {
        event.preventDefault();
        event.returnValue = "";
      }
    };

    window.addEventListener("beforeunload", handleBeforeUnload);

    return () => {
      window.removeEventListener("beforeunload", handleBeforeUnload);
    };
  }, [condition]);

  const handleConfirmNavigation = useCallback(() => {
    if (isSaving) return;
    setShowDialog(false);

    if (status === "blocked") {
      proceed();
    }
  }, [status, proceed, isSaving]);

  const handleCancelNavigation = useCallback(() => {
    if (isSaving) return;
    if (status === "blocked") {
      reset();
    }
    setShowDialog(false);
  }, [status, reset, isSaving]);

  const handleSaveAndLeave = useCallback(async () => {
    if (isSaving || !onSaveAndLeave) return;

    setIsSaving(true);
    try {
      await onSaveAndLeave();
      setShowDialog(false);
      if (status === "blocked") {
        proceed();
      }
    } catch (error) {
      toast({
        title: "Failed to save dashboard",
        description: "Please try again.",
        variant: "destructive",
      });
    } finally {
      setIsSaving(false);
    }
  }, [onSaveAndLeave, status, proceed, isSaving, toast]);

  const handleDialogOpenChange = useCallback(
    (open: boolean) => {
      if (!open && !isSaving) {
        handleCancelNavigation();
      }
    },
    [handleCancelNavigation, isSaving],
  );

  const showSaveAndLeave = Boolean(isFunction(onSaveAndLeave));

  const stayButton = (
    <DialogClose asChild>
      <Button
        variant="outline"
        onClick={handleCancelNavigation}
        disabled={isSaving}
      >
        {cancelText}
      </Button>
    </DialogClose>
  );

  const leaveButton = (
    <DialogClose asChild>
      <Button
        variant="destructive"
        onClick={handleConfirmNavigation}
        disabled={isSaving}
      >
        {confirmText}
      </Button>
    </DialogClose>
  );

  const saveAndLeaveButton = (
    <Button variant="default" onClick={handleSaveAndLeave} disabled={isSaving}>
      {isSaving && <Loader2 className="mr-2 size-4 animate-spin" />}
      {saveAndLeaveText}
    </Button>
  );

  const DialogComponent = (
    <Dialog open={showDialog} onOpenChange={handleDialogOpenChange}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          {showSaveAndLeave && (
            <>
              {leaveButton}
              <div className="flex-auto"></div>
            </>
          )}
          {stayButton}
          {showSaveAndLeave ? saveAndLeaveButton : leaveButton}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );

  return {
    DialogComponent,
  };
};

export default useNavigationBlocker;

import { useCallback, useEffect, useState } from "react";
import { useBlocker } from "@tanstack/react-router";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";

export interface UseNavigationBlockerOptions {
  condition: boolean;
  title?: string;
  description?: string;
  confirmText?: string;
  cancelText?: string;
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
}: UseNavigationBlockerOptions): UseNavigationBlockerResult => {
  const [showDialog, setShowDialog] = useState(false);

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
    setShowDialog(false);

    if (status === "blocked") {
      proceed();
    }
  }, [status, proceed]);

  const handleCancelNavigation = useCallback(() => {
    if (status === "blocked") {
      reset();
    }
    setShowDialog(false);
  }, [status, reset]);

  const handleDialogOpenChange = useCallback(
    (open: boolean) => {
      if (!open) {
        handleCancelNavigation();
      }
    },
    [handleCancelNavigation],
  );

  const DialogComponent = (
    <ConfirmDialog
      open={showDialog}
      setOpen={handleDialogOpenChange}
      onConfirm={handleConfirmNavigation}
      onCancel={handleCancelNavigation}
      title={title}
      description={description}
      confirmText={confirmText}
      cancelText={cancelText}
      confirmButtonVariant="destructive"
    />
  );

  return {
    DialogComponent,
  };
};

export default useNavigationBlocker;

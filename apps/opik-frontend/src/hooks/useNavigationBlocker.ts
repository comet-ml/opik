import { useCallback, useEffect, useState } from "react";
import { useBlocker } from "@tanstack/react-router";

export interface UseNavigationBlockerOptions {
  condition: boolean;
  title?: string;
  description?: string;
  confirmText?: string;
  cancelText?: string;
}

export interface UseNavigationBlockerResult {
  showDialog: boolean;
  setShowDialog: (show: boolean) => void;
  handleConfirmNavigation: () => void;
  handleCancelNavigation: () => void;
  handleDialogOpenChange: (open: boolean) => void;
  status: "idle" | "blocked" | "proceeding";
  title: string;
  description: string;
  confirmText: string;
  cancelText: string;
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

  return {
    showDialog,
    setShowDialog,
    handleConfirmNavigation,
    handleCancelNavigation,
    handleDialogOpenChange,
    status,
    title,
    description,
    confirmText,
    cancelText,
  };
};

export default useNavigationBlocker;


import { useCallback, useEffect, useState } from "react";
import { useBlocker } from "@tanstack/react-router";
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

export interface UseNavigationBlockerOptions {
  condition: boolean;
  title?: string;
  description?: string;
  confirmText?: string;
  cancelText?: string;
  onSaveAndLeave?: (proceed: () => void, cancel: () => void) => void;
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

  const handleSaveAndLeave = useCallback(() => {
    if (!onSaveAndLeave) return;

    setShowDialog(false);
    onSaveAndLeave(
      () => {
        if (status === "blocked") {
          proceed();
        }
      },
      () => {
        if (status === "blocked") {
          reset();
        }
      },
    );
  }, [onSaveAndLeave, status, proceed, reset]);

  const showSaveAndLeave = isFunction(onSaveAndLeave);

  const DialogComponent = (
    <Dialog
      open={showDialog}
      onOpenChange={(open) => !open && handleCancelNavigation()}
    >
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          {showSaveAndLeave && (
            <>
              <DialogClose asChild>
                <Button variant="destructive" onClick={handleConfirmNavigation}>
                  {confirmText}
                </Button>
              </DialogClose>
              <div className="flex-auto"></div>
            </>
          )}
          <DialogClose asChild>
            <Button variant="outline" onClick={handleCancelNavigation}>
              {cancelText}
            </Button>
          </DialogClose>
          {showSaveAndLeave ? (
            <Button variant="default" onClick={handleSaveAndLeave}>
              {saveAndLeaveText}
            </Button>
          ) : (
            <DialogClose asChild>
              <Button variant="destructive" onClick={handleConfirmNavigation}>
                {confirmText}
              </Button>
            </DialogClose>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );

  return {
    DialogComponent,
  };
};

export default useNavigationBlocker;

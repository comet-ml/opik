import React from "react";
import { Button, ButtonProps } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

type ConfirmDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  onConfirm?: () => void;
  onCancel?: () => void;
  title: string;
  description: string;
  confirmText: string;
  cancelText?: string;
  confirmButtonVariant?: ButtonProps["variant"];
};

const ConfirmDialog: React.FunctionComponent<ConfirmDialogProps> = ({
  open,
  setOpen,
  onConfirm,
  onCancel,
  title,
  description,
  confirmText = "Confirm",
  cancelText = "Cancel",
  confirmButtonVariant = "default",
}) => {
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline" onClick={onCancel}>
              {cancelText}
            </Button>
          </DialogClose>
          <DialogClose asChild>
            <Button
              type="submit"
              variant={confirmButtonVariant}
              onClick={onConfirm}
            >
              {confirmText}
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default ConfirmDialog;

import React from "react";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";

interface OverrideVersionDialogProps {
  open: boolean;
  setOpen: (open: boolean) => void;
  onConfirm: () => void;
}

const OverrideVersionDialog: React.FC<OverrideVersionDialogProps> = ({
  open,
  setOpen,
  onConfirm,
}) => {
  return (
    <ConfirmDialog
      open={open}
      setOpen={setOpen}
      onConfirm={onConfirm}
      title="Version conflict detected"
      description="Another version was created while you were editing. Do you want to override the latest version with your changes?"
      confirmText="Override and save"
      cancelText="Cancel"
      confirmButtonVariant="destructive"
    />
  );
};

export default OverrideVersionDialog;

import React from "react";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import BlueprintDiffTable, {
  type BlueprintVersionInfo,
} from "./BlueprintDiffTable";

type BlueprintDiffDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  base: BlueprintVersionInfo;
  diff: BlueprintVersionInfo;
};

const BlueprintDiffDialog: React.FC<BlueprintDiffDialogProps> = ({
  open,
  setOpen,
  base,
  diff,
}) => {
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[1200px]">
        <DialogHeader>
          <DialogTitle>
            Compare {base.label} → {diff.label}
          </DialogTitle>
        </DialogHeader>
        {open && <BlueprintDiffTable base={base} diff={diff} />}
      </DialogContent>
    </Dialog>
  );
};

export default BlueprintDiffDialog;

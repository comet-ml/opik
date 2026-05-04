import React from "react";

import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/ui/dialog";
import BlueprintDiffTable, {
  type BlueprintVersionInfo,
} from "@/v2/pages-shared/agent-configuration/BlueprintDiffDialog/BlueprintDiffTable";

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
            Comparing {base.label} to {diff.label}
          </DialogTitle>
        </DialogHeader>
        {open && <BlueprintDiffTable base={base} diff={diff} />}
      </DialogContent>
    </Dialog>
  );
};

export default BlueprintDiffDialog;

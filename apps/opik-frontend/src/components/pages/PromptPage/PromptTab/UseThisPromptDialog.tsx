import React from "react";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

type UseThisPromptDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
};

const UseThisPromptDialog: React.FunctionComponent<
  UseThisPromptDialogProps
> = ({ open, setOpen }) => {
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="h-[90vh] w-[90vw]">
        <DialogHeader>
          <DialogTitle>Use this prompt</DialogTitle>
        </DialogHeader>

        <div className="size-full overflow-y-auto"></div>
      </DialogContent>
    </Dialog>
  );
};

export default UseThisPromptDialog;

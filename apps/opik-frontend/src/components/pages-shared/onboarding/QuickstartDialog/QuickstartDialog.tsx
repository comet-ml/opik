import SideDialog from "@/components/shared/SideDialog/SideDialog";
import React from "react";
import FrameworkIntegrations from "../FrameworkIntegrations/FrameworkIntegrations";
import { SheetTitle } from "@/components/ui/sheet";

type QuickstartDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
};
const QuickstartDialog: React.FC<QuickstartDialogProps> = ({
  open,
  setOpen,
}) => {
  return (
    <SideDialog open={open} setOpen={setOpen}>
      <div className="flex w-full min-w-fit flex-col pb-12">
        <div className="pb-8">
          <SheetTitle>Quickstart guide</SheetTitle>
          <div className="comet-body-s m-auto mt-4 w-[468px] self-center text-center text-muted-slate">
            Select the framework and follow the instructions to integrate Opik
            with your own code or use our ready-to-run examples on the right.
          </div>
        </div>
        <FrameworkIntegrations />
      </div>
    </SideDialog>
  );
};

export default QuickstartDialog;

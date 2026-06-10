import React from "react";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogAutoScrollBody,
} from "@/ui/dialog";
import { Separator } from "@/ui/separator";
import HelpLinks from "./HelpLinks";
import InstallWithAITab from "@/v2/pages-shared/onboarding/InstallWithAITab";

type QuickInstallDialogProps = {
  open: boolean;
  onClose: () => void;
};

const QuickInstallDialog: React.FunctionComponent<QuickInstallDialogProps> = ({
  open,
  onClose,
}) => {
  const handleOpenChange = (newOpen: boolean) => {
    if (!newOpen) {
      onClose();
    }
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-[720px] gap-2">
        <DialogHeader>
          <DialogTitle>Quick install with AI assistants</DialogTitle>
        </DialogHeader>

        <DialogAutoScrollBody className="border-0">
          <InstallWithAITab traceReceived={false} showTraceStep={false} />

          <Separator className="my-6" />
          <HelpLinks
            onCloseParentDialog={onClose}
            title="Need some help?"
            description="Get help from your team or ours. Choose the option that works best for you."
          >
            <HelpLinks.InviteDev />
            <HelpLinks.Slack />
            <HelpLinks.WatchTutorial />
          </HelpLinks>
        </DialogAutoScrollBody>
      </DialogContent>
    </Dialog>
  );
};

export default QuickInstallDialog;

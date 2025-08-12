import React from "react";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogAutoScrollBody,
} from "@/components/ui/dialog";
import { Separator } from "@/components/ui/separator";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import useAppStore from "@/store/AppStore";
import { useUserApiKey } from "@/store/AppStore";
import { putConfigInCode } from "@/lib/formatCodeSnippets";
import { Integration } from "@/constants/integrations";
import HelpLinks from "./HelpLinks";

const CODE_BLOCK_1 = "pip install opik";

type IntegrationDetailsDialogProps = {
  selectedIntegration: Integration | null;
  onClose: () => void;
};

const IntegrationDetailsDialog: React.FunctionComponent<
  IntegrationDetailsDialogProps
> = ({ selectedIntegration, onClose }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const apiKey = useUserApiKey();

  if (!selectedIntegration) {
    return null;
  }

  const { code: codeWithConfig, lines } = putConfigInCode({
    code: selectedIntegration.code,
    workspaceName,
    apiKey,
    shouldMaskApiKey: true,
    withHighlight: true,
  });

  const { code: codeWithConfigToCopy } = putConfigInCode({
    code: selectedIntegration.code,
    workspaceName,
    apiKey,
    withHighlight: true,
  });

  const handleOpenChange = (newOpen: boolean) => {
    if (!newOpen) {
      onClose();
    }
  };

  return (
    <Dialog open={!!selectedIntegration} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-[920px]">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-3">
            <img
              src={selectedIntegration.icon}
              alt={selectedIntegration.title}
              className="size-8"
            />
            {selectedIntegration.title} Integration
          </DialogTitle>
        </DialogHeader>

        <DialogAutoScrollBody className="border-0">
          <div className="comet-body-s mb-6 text-muted-slate">
            Follow these steps to integrate {selectedIntegration.title} with
            Opik and start logging your LLM calls for analysis and performance
            monitoring.
          </div>

          <div className="mb-6 flex flex-col gap-6 rounded-md border bg-white p-6">
            <div>
              <div className="comet-body-s mb-3">
                1. Install Opik using pip from the command line.
              </div>
              <div className="min-h-7">
                <CodeHighlighter data={CODE_BLOCK_1} />
              </div>
            </div>
            <div>
              <div className="comet-body-s mb-3">
                2. Run the following code to get started with{" "}
                {selectedIntegration.title}
              </div>
              <CodeHighlighter
                data={codeWithConfig}
                copyData={codeWithConfigToCopy}
                highlightedLines={lines}
              />
            </div>
          </div>

          <Separator className="my-6" />

          <HelpLinks onCloseParentDialog={onClose}>
            <HelpLinks.InviteDev />
            <HelpLinks.Slack />
          </HelpLinks>
        </DialogAutoScrollBody>
      </DialogContent>
    </Dialog>
  );
};

export default IntegrationDetailsDialog;

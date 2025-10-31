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
import { ExternalLink } from "lucide-react";
import { IntegrationStep } from "./IntegrationStep";
import { useFeatureFlagVariantKey } from "posthog-js/react";
import { CODE_EXECUTOR_SERVICE_URL } from "@/api/api";
import CodeExecutor from "@/components/pages-shared/onboarding/CodeExecutor/CodeExecutor";

type IntegrationDetailsDialogProps = {
  selectedIntegration?: Integration;
  onClose: () => void;
};

const IntegrationDetailsDialog: React.FunctionComponent<
  IntegrationDetailsDialogProps
> = ({ selectedIntegration, onClose }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const apiKey = useUserApiKey();
  const variant = useFeatureFlagVariantKey("run-button-activation-test");

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

  const canExecuteCode =
    selectedIntegration.executionUrl &&
    selectedIntegration.executionLogs?.length &&
    apiKey &&
    Boolean(CODE_EXECUTOR_SERVICE_URL);

  const shouldShowCodeExecutor = canExecuteCode && variant === "test";

  const handleOpenChange = (newOpen: boolean) => {
    if (!newOpen) {
      onClose();
    }
  };

  return (
    <Dialog open={!!selectedIntegration} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-[920px] gap-2">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-3">
            {selectedIntegration.title} Integration
          </DialogTitle>
        </DialogHeader>

        <DialogAutoScrollBody className="border-0">
          <div className="comet-body-s mb-6 text-muted-slate">
            It all starts with a trace. Follow these quick steps to log your
            first set of LLM calls so you can use Opik to analyze them and
            improve performance.{" "}
            <a
              href={selectedIntegration.docsLink}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 text-primary hover:underline dark:text-primary-hover"
            >
              Read full guide
              <ExternalLink className="size-3" />
            </a>{" "}
            in our docs.
          </div>

          <IntegrationStep
            title="1. Install Opik using pip from the command line."
            description="Install Opik from the command line using pip."
            className="mb-6"
          >
            <div className="min-h-7">
              <CodeHighlighter data={selectedIntegration.installCommand} />
            </div>
          </IntegrationStep>
          <IntegrationStep
            title={`2. Run the following code to get started with ${selectedIntegration.title}`}
            className="mb-6"
          >
            {shouldShowCodeExecutor ? (
              <CodeExecutor
                executionUrl={selectedIntegration.executionUrl!}
                executionLogs={selectedIntegration.executionLogs!}
                data={codeWithConfig}
                copyData={codeWithConfigToCopy}
                apiKey={apiKey}
                workspaceName={workspaceName}
                highlightedLines={lines}
              />
            ) : (
              <CodeHighlighter
                data={codeWithConfig}
                copyData={codeWithConfigToCopy}
                highlightedLines={lines}
              />
            )}
          </IntegrationStep>

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

export default IntegrationDetailsDialog;

import React from "react";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogAutoScrollBody,
} from "@/ui/dialog";
import { Separator } from "@/ui/separator";
import CodeHighlighter from "@/shared/CodeHighlighter/CodeHighlighter";
import {
  INSTALL_OPIK_DEFAULT_DESCRIPTION,
  INSTALL_OPIK_DEFAULT_TITLE,
} from "@/constants/shared";
import useAppStore from "@/store/AppStore";
import { useUserApiKey } from "@/store/AppStore";
import useActiveProjectName from "@/hooks/useActiveProjectName";
import { putConfigInCode } from "@/lib/formatCodeSnippets";
import { Integration } from "@/constants/integrations";
import HelpLinks from "./HelpLinks";
import { ExternalLink } from "lucide-react";
import { IntegrationStep } from "./IntegrationStep";
import AdditionalIntegrationSteps from "@/shared/OnboardingIntegrationsPage/AdditionalIntegrationSteps";
import { useFeatureFlagVariantKey } from "posthog-js/react";
import { CODE_EXECUTOR_SERVICE_URL } from "@/api/api";
import CodeExecutor from "@/v2/pages-shared/onboarding/CodeExecutor/CodeExecutor";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import { useIsPhone } from "@/hooks/useIsPhone";
import {
  GUIDED_MOBILE_ONBOARDING_FLOW_FEATURE_FLAG_KEY,
  GUIDED_MOBILE_ONBOARDING_VARIANTS,
} from "@/v2/pages/GetStartedPage/AgentOnboarding/AgentOnboardingContext";

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
  // Group A (control) of the guided-mobile-onboarding A/B test (OPIK-7476) is
  // defined as the legacy onboarding flow *with* the Run button, so force the
  // code executor on for those phone users regardless of the separate
  // run-button-activation-test rollout.
  const { isPhone } = useIsPhone();
  const guidedMobileOnboardingVariant = useFeatureFlagVariantKey(
    GUIDED_MOBILE_ONBOARDING_FLOW_FEATURE_FLAG_KEY,
  );
  const isGuidedMobileOnboardingControl =
    isPhone &&
    guidedMobileOnboardingVariant === GUIDED_MOBILE_ONBOARDING_VARIANTS.CONTROL;
  const projectName = useActiveProjectName();
  const { themeMode } = useTheme();

  if (!selectedIntegration) {
    return null;
  }

  const iconSrc =
    themeMode === THEME_MODE.DARK && selectedIntegration.whiteIcon
      ? selectedIntegration.whiteIcon
      : selectedIntegration.icon;

  const { code: codeWithConfig, lines } = putConfigInCode({
    code: selectedIntegration.code,
    workspaceName,
    apiKey,
    shouldMaskApiKey: true,
    withHighlight: true,
    projectName,
  });

  const { code: codeWithConfigToCopy } = putConfigInCode({
    code: selectedIntegration.code,
    workspaceName,
    apiKey,
    withHighlight: true,
    projectName,
  });

  const canExecuteCode =
    selectedIntegration.executionUrl &&
    selectedIntegration.executionLogs?.length &&
    apiKey &&
    Boolean(CODE_EXECUTOR_SERVICE_URL);

  const shouldShowCodeExecutor =
    Boolean(canExecuteCode) &&
    (variant === "test" || isGuidedMobileOnboardingControl);

  const handleOpenChange = (newOpen: boolean) => {
    if (!newOpen) {
      onClose();
    }
  };

  return (
    <Dialog open={!!selectedIntegration} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-[920px] gap-2">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-1.5">
            <img
              alt={selectedIntegration.title}
              src={iconSrc}
              className="size-7 shrink-0"
            />
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
            title={
              selectedIntegration.installTitle ?? INSTALL_OPIK_DEFAULT_TITLE
            }
            description={
              selectedIntegration.installDescription ??
              INSTALL_OPIK_DEFAULT_DESCRIPTION
            }
            className="mb-6"
          >
            <div className="min-h-7">
              <CodeHighlighter data={selectedIntegration.installCommand} />
            </div>
          </IntegrationStep>
          {selectedIntegration.additionalSteps && (
            <AdditionalIntegrationSteps
              steps={selectedIntegration.additionalSteps}
              workspaceName={workspaceName}
              apiKey={apiKey}
              projectName={projectName}
              IntegrationStep={IntegrationStep}
              stepClassName="mb-6"
            />
          )}
          {selectedIntegration.code && (
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
                  language={selectedIntegration.codeLanguage}
                />
              )}
            </IntegrationStep>
          )}

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

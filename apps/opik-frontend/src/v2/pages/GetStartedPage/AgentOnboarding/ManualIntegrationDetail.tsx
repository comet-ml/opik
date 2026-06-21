import React from "react";
import { ExternalLink } from "lucide-react";
import CodeHighlighter from "@/shared/CodeHighlighter/CodeHighlighter";
import { IntegrationStep } from "@/v2/pages-shared/onboarding/IntegrationExplorer/components/IntegrationStep";
import { useActiveWorkspaceName, useUserApiKey } from "@/store/AppStore";
import { putConfigInCode } from "@/lib/formatCodeSnippets";
import { Integration } from "@/constants/integrations";
import {
  INSTALL_OPIK_DEFAULT_DESCRIPTION,
  INSTALL_OPIK_DEFAULT_TITLE,
} from "@/constants/shared";
import { useAgentOnboarding } from "./AgentOnboardingContext";
import AgentCopyButtons from "@/v2/pages-shared/onboarding/AgentCopyButtons";
import AdditionalIntegrationSteps from "@/shared/OnboardingIntegrationsPage/AdditionalIntegrationSteps";
import { Separator } from "@/ui/separator";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";

type ManualIntegrationDetailProps = {
  integration: Integration;
};

const ManualIntegrationDetail: React.FC<ManualIntegrationDetailProps> = ({
  integration,
}) => {
  const { agentName } = useAgentOnboarding();
  const workspaceName = useActiveWorkspaceName();
  const apiKey = useUserApiKey();
  const { themeMode } = useTheme();

  const iconSrc =
    themeMode === THEME_MODE.DARK && integration.whiteIcon
      ? integration.whiteIcon
      : integration.icon;

  const { code: codeWithConfig, lines } = putConfigInCode({
    code: integration.code,
    workspaceName,
    apiKey,
    shouldMaskApiKey: true,
    withHighlight: true,
    projectName: agentName,
  });

  const { code: codeWithConfigToCopy } = putConfigInCode({
    code: integration.code,
    workspaceName,
    apiKey,
    withHighlight: true,
    projectName: agentName,
  });

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1.5">
        <h3 className="comet-title-xs flex items-center gap-1.5">
          <img
            alt={integration.title}
            src={iconSrc}
            className="size-7 shrink-0"
          />
          Integrate Opik with {integration.title}
        </h3>
        <p className="comet-body-s text-muted-slate">
          It all starts with a trace. Follow these quick steps to log your first
          set of LLM calls so you can use Opik to analyze them and improve
          performance.{" "}
          <a
            href={integration.docsLink}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1 text-primary hover:underline dark:text-primary-hover"
          >
            Read the full guide
            <ExternalLink className="size-3" />
          </a>{" "}
          in our docs.
        </p>
      </div>
      <Separator />

      <AgentCopyButtons agentName={agentName} />

      <IntegrationStep
        title={integration.installTitle ?? INSTALL_OPIK_DEFAULT_TITLE}
        description={
          integration.installDescription ?? INSTALL_OPIK_DEFAULT_DESCRIPTION
        }
        className="mb-2 border-0 p-0"
      >
        <div className="flex flex-col gap-3">
          <div className="min-h-7 overflow-hidden rounded-md border">
            <CodeHighlighter data={integration.installCommand} />
          </div>
          {!integration.installTitle && (
            <div className="overflow-hidden rounded-md border">
              <CodeHighlighter
                data={`import opik\n\nopik.configure(use_local=False, project_name="${agentName}")`}
                highlightedLines={[1, 3]}
              />
            </div>
          )}
        </div>
      </IntegrationStep>

      {integration.additionalSteps && (
        <AdditionalIntegrationSteps
          steps={integration.additionalSteps}
          workspaceName={workspaceName}
          apiKey={apiKey}
          projectName={agentName}
          IntegrationStep={IntegrationStep}
          stepClassName="mb-2 border-0 p-0"
          codeWrapperClassName="min-h-7 overflow-hidden rounded-md border"
        />
      )}
      {integration.code && (
        <IntegrationStep
          title={`2. Run the following code to get started with ${integration.title}`}
          className="mb-2 border-0 p-0"
        >
          <div className="overflow-hidden rounded-md border">
            <CodeHighlighter
              data={codeWithConfig}
              copyData={codeWithConfigToCopy}
              highlightedLines={lines}
              language={integration.codeLanguage}
            />
          </div>
        </IntegrationStep>
      )}
    </div>
  );
};

export default ManualIntegrationDetail;

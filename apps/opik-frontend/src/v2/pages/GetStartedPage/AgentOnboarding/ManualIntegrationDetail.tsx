import React from "react";
import { ExternalLink } from "lucide-react";
import CodeHighlighter from "@/shared/CodeHighlighter/CodeHighlighter";
import { IntegrationStep } from "@/v2/pages-shared/onboarding/IntegrationExplorer/components/IntegrationStep";
import { useActiveWorkspaceName, useUserApiKey } from "@/store/AppStore";
import { putConfigInCode } from "@/lib/formatCodeSnippets";
import { Integration } from "@/constants/integrations";
import { INSTALL_OPIK_SECTION_TITLE } from "@/constants/shared";
import { useAgentOnboarding } from "./AgentOnboardingContext";
import AgentCopyButtons from "./AgentCopyButtons";
import { Separator } from "@/ui/separator";

type ManualIntegrationDetailProps = {
  integration: Integration;
};

const ManualIntegrationDetail: React.FC<ManualIntegrationDetailProps> = ({
  integration,
}) => {
  const { agentName } = useAgentOnboarding();
  const workspaceName = useActiveWorkspaceName();
  const apiKey = useUserApiKey();

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
        <h3 className="comet-title-xs">
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

      <AgentCopyButtons />

      <IntegrationStep
        title={`${INSTALL_OPIK_SECTION_TITLE}.`}
        description="Install Opik from the command line using pip."
        className="mb-2 border-0 p-0"
      >
        <div className="flex flex-col gap-3">
          <div className="min-h-7 overflow-hidden rounded-md border">
            <CodeHighlighter data={integration.installCommand} />
          </div>
          <div className="overflow-hidden rounded-md border">
            <CodeHighlighter
              data={`import opik\n\nopik.configure(use_local=False, project_name="${agentName}")`}
            />
          </div>
        </div>
      </IntegrationStep>

      <IntegrationStep
        title={`2. Run the following code to get started with ${integration.title}`}
        className="mb-2 border-0 p-0"
      >
        <div className="overflow-hidden rounded-md border">
          <CodeHighlighter
            data={codeWithConfig}
            copyData={codeWithConfigToCopy}
            highlightedLines={lines}
          />
        </div>
      </IntegrationStep>
    </div>
  );
};

export default ManualIntegrationDetail;

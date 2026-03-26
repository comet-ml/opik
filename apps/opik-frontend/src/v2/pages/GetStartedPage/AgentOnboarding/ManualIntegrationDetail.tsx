import React from "react";
import { ExternalLink } from "lucide-react";
import CodeHighlighter from "@/shared/CodeHighlighter/CodeHighlighter";
import { IntegrationStep } from "@/v2/pages-shared/onboarding/IntegrationExplorer/components/IntegrationStep";
import useAppStore, { useUserApiKey } from "@/store/AppStore";
import { putConfigInCode } from "@/lib/formatCodeSnippets";
import { Integration } from "@/constants/integrations";
import { INSTALL_OPIK_SECTION_TITLE } from "@/constants/shared";

type ManualIntegrationDetailProps = {
  integration: Integration;
};

const ManualIntegrationDetail: React.FC<ManualIntegrationDetailProps> = ({
  integration,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const apiKey = useUserApiKey();

  const { code: codeWithConfig, lines } = putConfigInCode({
    code: integration.code,
    workspaceName,
    apiKey,
    shouldMaskApiKey: true,
    withHighlight: true,
  });

  const { code: codeWithConfigToCopy } = putConfigInCode({
    code: integration.code,
    workspaceName,
    apiKey,
    withHighlight: true,
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

      <IntegrationStep
        title={`${INSTALL_OPIK_SECTION_TITLE}.`}
        description="Install Opik from the command line using pip."
        className="mb-2"
      >
        <div className="flex flex-col gap-3">
          <div className="min-h-7">
            <CodeHighlighter data={integration.installCommand} />
          </div>
          <CodeHighlighter
            data={`import opik\n\nopik.configure(use_local=False)`}
          />
        </div>
      </IntegrationStep>

      <IntegrationStep
        title={`2. Run the following code to get started with ${integration.title}`}
        className="mb-2"
      >
        <CodeHighlighter
          data={codeWithConfig}
          copyData={codeWithConfigToCopy}
          highlightedLines={lines}
        />
      </IntegrationStep>
    </div>
  );
};

export default ManualIntegrationDetail;

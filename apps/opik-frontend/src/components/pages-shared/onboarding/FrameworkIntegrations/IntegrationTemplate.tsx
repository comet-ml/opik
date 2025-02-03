import React from "react";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import useAppStore from "@/store/AppStore";
import { CODE_EXECUTOR_SERVICE_URL } from "@/api/api";
import { buildApiKeyConfig, buildWorkspaceNameConfig } from "@/lib/utils";
import CodeExecutor from "../CodeExecutor/CodeExecutor";
import { OPIK_URL_OVERRIDE_CONFIG } from "@/constants/shared";

const CODE_BLOCK_1 = "pip install opik";

export const OPIK_API_KEY_TEMPLATE = "# INJECT_OPIK_CONFIGURATION";

type PutConfigInCodeArgs = {
  code: string;
  workspaceName: string;
  apiKey?: string;
  maskApiKey?: boolean;
};
const putConfigInCode = ({
  code,
  workspaceName,
  apiKey,
  maskApiKey,
}: PutConfigInCodeArgs): string => {
  if (apiKey) {
    const apiKeyConfig = buildApiKeyConfig(apiKey, maskApiKey);
    const workspaceConfig = buildWorkspaceNameConfig(workspaceName);

    return code.replace(
      OPIK_API_KEY_TEMPLATE,
      `${apiKeyConfig}\n${workspaceConfig}`,
    );
  }

  return code.replace(OPIK_API_KEY_TEMPLATE, OPIK_URL_OVERRIDE_CONFIG);
};

type IntegrationTemplateProps = {
  apiKey?: string;
  code: string;
  executionUrl?: string;
  executionLogs: string[];
};

const IntegrationTemplate: React.FC<IntegrationTemplateProps> = ({
  apiKey,
  code,
  executionUrl,
  executionLogs,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const codeWithConfig = putConfigInCode({
    code,
    workspaceName,
    apiKey,
    maskApiKey: true,
  });
  const codeWithConfigToCopy = putConfigInCode({ code, workspaceName, apiKey });

  const canExecuteCode =
    executionUrl && apiKey && Boolean(CODE_EXECUTOR_SERVICE_URL);

  return (
    <div className="flex flex-col gap-6 rounded-md border bg-white p-6">
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
          2. Run the following code to get started
        </div>
        {canExecuteCode ? (
          <CodeExecutor
            executionUrl={executionUrl}
            executionLogs={executionLogs}
            data={codeWithConfig}
            copyData={codeWithConfigToCopy}
            apiKey={apiKey}
            workspaceName={workspaceName}
          />
        ) : (
          <CodeHighlighter
            data={codeWithConfig}
            copyData={codeWithConfigToCopy}
          />
        )}
      </div>
    </div>
  );
};

export default IntegrationTemplate;

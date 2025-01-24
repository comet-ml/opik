import React from "react";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import useAppStore from "@/store/AppStore";
import { BASE_API_URL, CODE_EXECUTOR_SERVICE_URL } from "@/api/api";
import { maskAPIKey } from "@/lib/utils";
import CodeExecutor from "../CodeExecutor/CodeExecutor";

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
    return code.replace(
      OPIK_API_KEY_TEMPLATE,
      `os.environ["OPIK_API_KEY"] = "${
        maskApiKey ? maskAPIKey(apiKey) : apiKey
      }"\nos.environ["OPIK_WORKSPACE"] = "${workspaceName}"`,
    );
  }

  return code.replace(
    OPIK_API_KEY_TEMPLATE,
    `os.environ["OPIK_URL_OVERRIDE"] = "${window.location.origin}${BASE_API_URL}"`,
  );
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

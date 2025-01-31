import React from "react";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import useAppStore from "@/store/AppStore";
import { CODE_EXECUTOR_SERVICE_URL } from "@/api/api";
import {
  buildApiKeyConfig,
  buildOpikUrlOverrideConfig,
  buildWorkspaceNameConfig,
} from "@/lib/utils";
import CodeExecutor from "../CodeExecutor/CodeExecutor";
import { OPIK_HIGHLIGHT_LINE_TEMPLATE } from "@/constants/shared";

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
}: PutConfigInCodeArgs): { code: string; lines: number[] } => {
  let patchedCode = "";

  if (apiKey) {
    const apiKeyConfig = buildApiKeyConfig(apiKey, maskApiKey, true);
    const workspaceConfig = buildWorkspaceNameConfig(workspaceName, true);

    patchedCode = code.replace(
      OPIK_API_KEY_TEMPLATE,
      `${apiKeyConfig}\n${workspaceConfig}`,
    );
  } else {
    patchedCode = code.replace(
      OPIK_API_KEY_TEMPLATE,
      buildOpikUrlOverrideConfig(true),
    );
  }

  return {
    code: patchedCode.replaceAll(OPIK_HIGHLIGHT_LINE_TEMPLATE, ""),
    lines: patchedCode.split("\n").reduce<number[]>((acc, line, idx) => {
      if (line.includes(OPIK_HIGHLIGHT_LINE_TEMPLATE)) {
        acc.push(idx + 1);
      }

      return acc;
    }, []),
  };
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
  const { code: codeWithConfig, lines } = putConfigInCode({
    code,
    workspaceName,
    apiKey,
    maskApiKey: true,
  });
  const { code: codeWithConfigToCopy } = putConfigInCode({
    code,
    workspaceName,
    apiKey,
  });

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
            highlightedLines={lines}
          />
        ) : (
          <CodeHighlighter
            data={codeWithConfig}
            copyData={codeWithConfigToCopy}
            highlightedLines={lines}
          />
        )}
      </div>
    </div>
  );
};

export default IntegrationTemplate;

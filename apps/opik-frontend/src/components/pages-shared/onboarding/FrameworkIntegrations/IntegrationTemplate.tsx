import React from "react";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import useAppStore from "@/store/AppStore";
import { CODE_EXECUTOR_SERVICE_URL } from "@/api/api";
import CodeExecutor from "../CodeExecutor/CodeExecutor";
import { putConfigInCode } from "@/lib/formatCodeSnippets";
import { useIsPhone } from "@/hooks/useIsPhone";
import CopyButton from "@/components/shared/CopyButton/CopyButton";

const CODE_BLOCK_1 = "pip install opik";

type IntegrationTemplateProps = {
  apiKey?: string;
  code: string;
  executionUrl?: string;
  executionLogs?: string[];
  withLineHighlights?: boolean;
  onRunCodeCallback?: () => void;
};

const IntegrationTemplate: React.FC<IntegrationTemplateProps> = ({
  apiKey,
  code,
  executionUrl,
  executionLogs = [],
  withLineHighlights,
  onRunCodeCallback,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { isPhonePortrait } = useIsPhone();

  const { code: codeWithConfig, lines } = putConfigInCode({
    code,
    workspaceName,
    apiKey,
    shouldMaskApiKey: true,
    withHighlight: withLineHighlights,
  });
  const { code: codeWithConfigToCopy } = putConfigInCode({
    code,
    workspaceName,
    apiKey,
    withHighlight: withLineHighlights,
  });

  const canExecuteCode =
    executionUrl &&
    executionLogs.length &&
    apiKey &&
    Boolean(CODE_EXECUTOR_SERVICE_URL);

  const CodeBlockWithHeader = ({
    title,
    children,
    copyText,
  }: {
    title: string;
    children: React.ReactNode;
    copyText?: string;
  }) => (
    <div className="overflow-hidden rounded-md border border-border bg-primary-foreground">
      <div className="flex items-center justify-between border-b border-border px-3 py-1.5">
        <div className="comet-body-xs text-muted-slate">{title}</div>
        {copyText && (
          <CopyButton
            message="Successfully copied code"
            text={copyText}
            tooltipText="Copy code"
          />
        )}
      </div>
      <div className="[&>div>.absolute]:!hidden [&_.cm-editor]:!bg-primary-foreground [&_.cm-gutters]:!bg-primary-foreground">
        {children}
      </div>
    </div>
  );

  if (isPhonePortrait) {
    return (
      <div className="flex flex-col gap-6">
        <div>
          <div className="comet-body-s-accented mb-3 overflow-x-auto whitespace-nowrap">
            1. Install Opik using pip from the command line
          </div>
          <CodeBlockWithHeader title="Terminal" copyText={CODE_BLOCK_1}>
            <CodeHighlighter data={CODE_BLOCK_1} />
          </CodeBlockWithHeader>
        </div>
        <div>
          <div className="comet-body-s-accented mb-3 overflow-x-auto whitespace-nowrap">
            2. Run the following code to get started
          </div>
          {canExecuteCode ? (
            <CodeBlockWithHeader title="Python" copyText={codeWithConfigToCopy}>
              <CodeExecutor
                executionUrl={executionUrl}
                executionLogs={executionLogs}
                data={codeWithConfig}
                copyData={codeWithConfigToCopy}
                apiKey={apiKey}
                workspaceName={workspaceName}
                highlightedLines={lines}
                onRunCodeCallback={onRunCodeCallback}
              />
            </CodeBlockWithHeader>
          ) : (
            <CodeBlockWithHeader title="Python" copyText={codeWithConfigToCopy}>
              <CodeHighlighter
                data={codeWithConfig}
                copyData={codeWithConfigToCopy}
                highlightedLines={lines}
              />
            </CodeBlockWithHeader>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6 rounded-md border bg-background p-6">
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
            onRunCodeCallback={onRunCodeCallback}
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

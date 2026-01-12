import React from "react";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import useAppStore from "@/store/AppStore";
import { CODE_EXECUTOR_SERVICE_URL } from "@/api/api";
import CodeExecutor from "../CodeExecutor/CodeExecutor";
import { putConfigInCode } from "@/lib/formatCodeSnippets";
import CopyButton from "@/components/shared/CopyButton/CopyButton";
import { useIsPhone } from "@/hooks/useIsPhone";

const CODE_BLOCK_1 = "pip install opik";

type IntegrationTemplateProps = {
  apiKey?: string;
  code: string;
  executionUrl?: string;
  executionLogs?: string[];
  withLineHighlights?: boolean;
  onRunCodeCallback?: () => void;
};

type SectionTitleProps = {
  children: React.ReactNode;
};

const SectionTitle: React.FC<SectionTitleProps> = ({ children }) => (
  <div className="comet-body-s-accented md:comet-body-s mb-3 overflow-x-auto whitespace-nowrap">
    {children}
  </div>
);

type CodeBlockWithHeaderProps = {
  title: string;
  children: React.ReactNode;
  copyText?: string;
};

const CodeBlockWithHeader: React.FC<CodeBlockWithHeaderProps> = ({
  title,
  children,
  copyText,
}) => (
  <div className="overflow-hidden rounded-md border border-border bg-primary-foreground">
    <div className="flex items-center justify-between border-b border-border px-3">
      <div className="comet-body-xs text-muted-slate">{title}</div>
      {copyText && (
        <div className="-mr-2">
          <CopyButton
            message="Successfully copied code"
            text={copyText}
            tooltipText="Copy code"
          />
        </div>
      )}
    </div>
    <div className="[&>div>.absolute]:!hidden [&_.cm-editor]:!bg-primary-foreground [&_.cm-gutters]:!bg-primary-foreground">
      {children}
    </div>
  </div>
);

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

  const renderCodeSection = () => {
    if (canExecuteCode) {
      return (
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
      );
    }

    return (
      <CodeHighlighter
        data={codeWithConfig}
        copyData={codeWithConfigToCopy}
        highlightedLines={lines}
      />
    );
  };

  const renderInstallSection = () => (
    <div>
      <SectionTitle>
        1. Install Opik using pip from the command line
      </SectionTitle>
      {isPhonePortrait ? (
        <CodeBlockWithHeader title="Terminal" copyText={CODE_BLOCK_1}>
          <CodeHighlighter data={CODE_BLOCK_1} />
        </CodeBlockWithHeader>
      ) : (
        <div className="min-h-7">
          <CodeHighlighter data={CODE_BLOCK_1} />
        </div>
      )}
    </div>
  );

  const renderRunCodeSection = () => (
    <div>
      <SectionTitle>2. Run the following code to get started</SectionTitle>
      {isPhonePortrait ? (
        <CodeBlockWithHeader
          title="Python"
          copyText={canExecuteCode ? undefined : codeWithConfigToCopy}
        >
          {renderCodeSection()}
        </CodeBlockWithHeader>
      ) : (
        renderCodeSection()
      )}
    </div>
  );

  return (
    <div className="flex flex-col gap-6 md:rounded-md md:border md:bg-background md:p-6">
      {renderInstallSection()}
      {renderRunCodeSection()}
    </div>
  );
};

export default IntegrationTemplate;

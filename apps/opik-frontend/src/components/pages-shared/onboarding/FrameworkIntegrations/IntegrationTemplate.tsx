import React from "react";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import CodeBlockWithHeader from "@/components/shared/CodeBlockWithHeader/CodeBlockWithHeader";
import CodeSectionTitle from "@/components/shared/CodeSectionTitle/CodeSectionTitle";
import InstallOpikSection from "@/components/shared/InstallOpikSection/InstallOpikSection";
import useAppStore from "@/store/AppStore";
import { CODE_EXECUTOR_SERVICE_URL } from "@/api/api";
import CodeExecutor from "../CodeExecutor/CodeExecutor";
import { putConfigInCode } from "@/lib/formatCodeSnippets";
import { useIsPhone } from "@/hooks/useIsPhone";
import { INSTALL_OPIK_SECTION_TITLE } from "@/constants/shared";

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

  const renderRunCodeSection = () => (
    <div>
      <CodeSectionTitle>
        2. Run the following code to get started
      </CodeSectionTitle>
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
      <InstallOpikSection title={INSTALL_OPIK_SECTION_TITLE} />
      {renderRunCodeSection()}
    </div>
  );
};

export default IntegrationTemplate;

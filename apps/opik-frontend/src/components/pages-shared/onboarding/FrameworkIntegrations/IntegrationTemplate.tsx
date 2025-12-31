import React, { useState } from "react";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import useAppStore from "@/store/AppStore";
import { CODE_EXECUTOR_SERVICE_URL } from "@/api/api";
import CodeExecutor from "../CodeExecutor/CodeExecutor";
import { putConfigInCode } from "@/lib/formatCodeSnippets";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
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
  const [expandedItem, setExpandedItem] = useState<string>("install");

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

  const installSection = (
    <div>
      <div className="comet-body-s mb-3">
        Install Opik using pip from the command line
      </div>
      <div className="min-h-7">
        <CodeHighlighter data={CODE_BLOCK_1} />
      </div>
    </div>
  );

  const runCodeSection = (
    <div>
      <div className="comet-body-s mb-3">
        Run the following code to get started
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
  );

  if (isPhonePortrait) {
    return (
      <div className="flex flex-col gap-6 rounded-md border bg-background p-6">
        <Accordion
          type="single"
          collapsible
          value={expandedItem}
          onValueChange={setExpandedItem}
        >
          <AccordionItem value="install">
            <AccordionTrigger className="comet-body-s-accented hover:no-underline">
              Install Opik
            </AccordionTrigger>
            <AccordionContent>{installSection}</AccordionContent>
          </AccordionItem>
          <AccordionItem value="run-code">
            <AccordionTrigger className="comet-body-s-accented hover:no-underline">
              Run the code
            </AccordionTrigger>
            <AccordionContent>{runCodeSection}</AccordionContent>
          </AccordionItem>
        </Accordion>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6 rounded-md border bg-background p-6">
      {installSection}
      {runCodeSection}
    </div>
  );
};

export default IntegrationTemplate;

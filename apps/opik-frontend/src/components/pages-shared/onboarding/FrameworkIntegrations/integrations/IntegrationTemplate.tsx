import React from "react";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import { FrameworkIntegrationComponentProps } from "@/components/pages-shared/onboarding/FrameworkIntegrations/types";

const CODE_BLOCK_1 = "pip install opik";

export const OPIK_API_KEY_TEMPLATE = "{OPIK_API_KEY}";

const putApiKeyInCode = (code: string, apiKey?: string): string => {
  if (apiKey) {
    return code.replace(
      OPIK_API_KEY_TEMPLATE,
      `\nos.environ["OPIK_API_KEY"] = "${apiKey}"\n`,
    );
  }

  return code.replace(OPIK_API_KEY_TEMPLATE, "");
};

type IntegrationTemplateProps = FrameworkIntegrationComponentProps & {
  codeTitle?: string;
  code: string;
};

const IntegrationTemplate: React.FC<IntegrationTemplateProps> = ({
  apiKey,
  codeTitle,
  code,
}) => {
  console.log("code", putApiKeyInCode(code, apiKey).trim());
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
          2. {codeTitle || "Run the following code to get started"}
        </div>
        <CodeHighlighter data={putApiKeyInCode(code, apiKey)} />
      </div>
    </div>
  );
};

export default IntegrationTemplate;

import React from "react";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import { FrameworkIntegrationComponentProps } from "@/components/pages-shared/onboarding/FrameworkIntegrations/types";

const CODE_BLOCK_1 = "pip install opik";

const CODE_BLOCK_2 = `import opik
opik.configure()`;

const CODE_BLOCK_2_LOCAL = `import opik
opik.configure(use_local=True)`;

type IntegrationTemplateProps = FrameworkIntegrationComponentProps & {
  codeTitle: string;
  code: string;
};

const IntegrationTemplate: React.FC<IntegrationTemplateProps> = ({
  apiKey,
  codeTitle,
  code,
}) => {
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
        <div className="comet-body-s mb-3">2. Configure the Opik SDK</div>
        <div className="min-h-12">
          <CodeHighlighter data={apiKey ? CODE_BLOCK_2 : CODE_BLOCK_2_LOCAL} />
        </div>
      </div>
      <div>
        <div className="comet-body-s mb-3">3. {codeTitle}</div>
        <CodeHighlighter data={code} />
      </div>
    </div>
  );
};

export default IntegrationTemplate;

import React from "react";
import { IntegrationComponentProps } from "@/components/pages/QuickstartPage/integrations/types";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";

const CODE_BLOCK_1 = "pip install opik";

const CODE_BLOCK_2 = `import opik
opik.configure()`;

const CODE_BLOCK_2_LOCAL = `import opik
opik.configure(use_local=True)`;

type IntegrationTemplateProps = IntegrationComponentProps & {
  integration: string;
  url: string;
  codeTitle: string;
  code: string;
};

const IntegrationTemplate: React.FC<IntegrationTemplateProps> = ({
  apiKey,
  integration,
  url,
  codeTitle,
  code,
}) => {
  return (
    <div className="flex flex-col gap-6 rounded-md border bg-white p-8">
      <div className="flex items-center gap-1.5">
        <img alt={integration} src={url} className="size-[22px] shrink-0" />
        <h2 className="comet-title-s">Integrate Opik with your own code </h2>
      </div>
      <div className="flex flex-col gap-5">
        <div>
          <div className="comet-body-s mb-4">
            Install Opik using pip from the command line.
          </div>
          <CodeHighlighter data={CODE_BLOCK_1} />
        </div>
        <div>
          <div className="comet-body-s mb-4">Configure the Opik SDK</div>
          <CodeHighlighter data={apiKey ? CODE_BLOCK_2 : CODE_BLOCK_2_LOCAL} />
        </div>
        <div>
          <div className="comet-body-s mb-4">{codeTitle}</div>
          <CodeHighlighter data={code} />
        </div>
      </div>
    </div>
  );
};

export default IntegrationTemplate;

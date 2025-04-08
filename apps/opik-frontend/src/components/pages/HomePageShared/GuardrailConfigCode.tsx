import React from "react";
import ConfiguredCodeHighlighter from "@/components/pages-shared/onboarding/ConfiguredCodeHighlighter/ConfiguredCodeHighlighter";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";

type GuardrailConfigCodeProps = {
  codeImportNames: string[];
  codeList: string[];
  projectName?: string;
};

const GuardrailConfigCode: React.FC<GuardrailConfigCodeProps> = ({
  codeImportNames,
  codeList,
  projectName,
}) => {
  return (
    <>
      <div className="comet-body-s text-foreground-secondary">
        1. Install Opik using pip from the command line
      </div>
      <CodeHighlighter data={"pip install opik"} />
      <div className="comet-body-s mt-4 text-foreground-secondary">
        2. Use the following code to configure your guardrail
      </div>
      <ConfiguredCodeHighlighter
        projectName={projectName}
        code={`import os
from opik.guardrails import Guardrail${
          codeImportNames.length > 0 ? `, ${codeImportNames.join(", ")}` : ""
        }
from opik import exceptions

# INJECT_OPIK_CONFIGURATION

guard = Guardrail(
    guards=[
        ${codeList.join(",\n        ")}
    ]
)

result = guard.validate("How can I start with evaluation in Opik platform?")
# Guardrail passes

try:
    result = guard.validate("Where should I invest my money?")
except exceptions.GuardrailValidationFailed as e:
    print("Guardrail failed:", e)

try:
    result = guard.validate("John Doe, here is my card number 4111111111111111 how can I use it in Opik platform?.")
except exceptions.GuardrailValidationFailed as e:
    print("Guardrail failed:", e)`}
      />
    </>
  );
};

export default GuardrailConfigCode;

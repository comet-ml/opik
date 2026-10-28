import React, { useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";

import { Description } from "@/ui/description";
import CopyButton from "@/shared/CopyButton/CopyButton";
import CodeHighlighter from "@/shared/CodeHighlighter/CodeHighlighter";
import { SUPPORTED_LANGUAGE } from "@/constants/codeLanguage";

type GuardrailPolicyCodeSectionProps = {
  policyName: string;
};

/**
 * Answers "and now what?" once a policy is saved: an application loads the policy by name.
 */
const GuardrailPolicyCodeSection: React.FC<GuardrailPolicyCodeSectionProps> = ({
  policyName,
}) => {
  const [isExpanded, setIsExpanded] = useState(false);

  const name = policyName.trim() || "my-policy";
  const escapedName = name.replace(/\\/g, "\\\\").replace(/"/g, '\\"');

  const snippet = `from opik import exceptions
from opik.guardrails import Guardrail

guardrail = Guardrail.from_stored_policies(names=["${escapedName}"])

try:
    guardrail.validate("How can I start with evaluation in Opik platform?")
except exceptions.GuardrailValidationFailed as e:
    print("Guardrail failed:", e)`;

  return (
    <div className="overflow-hidden rounded-md border border-border bg-primary-foreground">
      <div className="flex h-8 items-center justify-between gap-2 pr-2">
        <button
          type="button"
          className="comet-body-xs flex h-full flex-1 items-center gap-1 px-3 font-normal text-muted-slate"
          onClick={() => setIsExpanded(!isExpanded)}
        >
          {isExpanded ? (
            <ChevronDown className="size-3 shrink-0" />
          ) : (
            <ChevronRight className="size-3 shrink-0" />
          )}
          Use in your code
        </button>
        {isExpanded && (
          <CopyButton
            text={snippet}
            tooltipText="Copy snippet"
            size="icon-2xs"
            variant="ghost"
          />
        )}
      </div>
      {isExpanded && (
        <>
          <div className="px-1.5">
            <CodeHighlighter
              data={snippet}
              language={SUPPORTED_LANGUAGE.python}
              hideCopy
              transparent
            />
          </div>
          <Description className="block px-3 pb-2">
            An application references a policy by name. Requires an upcoming
            release of the Opik Python SDK.
          </Description>
        </>
      )}
    </div>
  );
};

export default GuardrailPolicyCodeSection;

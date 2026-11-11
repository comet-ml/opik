/** The Python an application runs to load a policy by name, with the name as a valid string literal. */
export const buildPolicySnippet = (policyName: string): string => {
  const name = policyName.trim() || "my-policy";
  const escapedName = name
    .replace(/\\/g, "\\\\")
    .replace(/"/g, '\\"')
    .replace(/\n/g, "\\n")
    .replace(/\r/g, "\\r")
    .replace(/\t/g, "\\t");

  return `from opik import exceptions
from opik.guardrails import Guardrail

guardrail = Guardrail.from_stored_policies(names=["${escapedName}"])

try:
    guardrail.validate("How can I start with evaluation in Opik platform?")
except exceptions.GuardrailValidationFailed as e:
    print("Guardrail failed:", e)`;
};

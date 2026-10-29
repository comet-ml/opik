import React from "react";

import { Description } from "@/ui/description";
import { Tag } from "@/ui/tag";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { SCOPE_ENTITY_LABEL } from "@/v2/pages-shared/automations/RuleEditorPanel/constants";

const PromptVariableHint: React.FC<{ scope: EVALUATORS_RULE_SCOPE }> = ({
  scope,
}) => (
  <Description className="px-0.5">
    Use{" "}
    <Tag variant="green" className="px-1 align-top font-mono">
      {"{{"}
    </Tag>{" "}
    <span>
      {`to insert variables, which will automatically pull data from ${SCOPE_ENTITY_LABEL[scope]} when the evaluator runs.`}
    </span>
  </Description>
);

export default PromptVariableHint;

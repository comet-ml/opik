import React from "react";

import NavigationTag from "@/shared/NavigationTag";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";
import { ExperimentPromptVersion } from "@/types/datasets";
import { formatPromptVersionLabel } from "@/lib/experiments";

type PromptVersionTagProps = {
  promptVersion: ExperimentPromptVersion;
};

const PromptVersionTag: React.FunctionComponent<PromptVersionTagProps> = ({
  promptVersion,
}) => (
  <NavigationTag
    id={promptVersion.prompt_id}
    name={formatPromptVersionLabel(promptVersion)}
    resource={RESOURCE_TYPE.prompt}
    search={{ activeVersionId: promptVersion.id }}
  />
);

export default PromptVersionTag;

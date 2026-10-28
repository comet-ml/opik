import React from "react";
import { TriangleAlert } from "lucide-react";

import useLlmModels from "@/api/llm/useLlmModels";
import {
  formatRetirementDate,
  getModelRetirement,
} from "@/lib/modelRetirement";
import { Tag } from "@/ui/tag";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

type ModelRetirementTagProps = {
  model: string | undefined | null;
  provider: string | undefined | null;
};

const ModelRetirementTag: React.FC<ModelRetirementTagProps> = ({
  model,
  provider,
}) => {
  const { data } = useLlmModels();
  const retirement = getModelRetirement(data, model, provider);
  if (!retirement) return null;

  const date = formatRetirementDate(retirement.date);
  const label = retirement.retired ? "Retired" : `Retires ${date}`;
  const tooltip = retirement.retired
    ? `The provider retired this model on ${date}. Calls to it can fail; pick another model.`
    : `The provider retires this model on ${date}. Pick another model before then.`;

  return (
    <TooltipWrapper content={tooltip}>
      <Tag
        variant="orange"
        size="sm"
        className="inline-flex shrink-0 items-center gap-1"
        data-testid="model-retirement-tag"
      >
        <TriangleAlert className="size-3" />
        {label}
      </Tag>
    </TooltipWrapper>
  );
};

export default ModelRetirementTag;

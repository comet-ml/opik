import React from "react";
import { FlaskConical } from "lucide-react";
import { Tag } from "@/components/ui/tag";
import {
  RESOURCE_MAP,
  RESOURCE_TYPE,
} from "@/components/shared/ResourceLink/ResourceLink";

interface ExperimentTagProps {
  experimentName?: string;
  count?: number;
}

const ExperimentTag: React.FC<ExperimentTagProps> = ({
  experimentName,
  count,
}) => {
  const { color } = RESOURCE_MAP[RESOURCE_TYPE.experiment];

  if (experimentName) {
    return (
      <Tag size="md" variant="transparent" className="flex items-center gap-1">
        <FlaskConical className="size-3 shrink-0" style={{ color }} />
        <div className="comet-body-s-accented min-w-0 truncate text-muted-slate">
          {experimentName}
        </div>
      </Tag>
    );
  }

  if (count !== undefined) {
    return (
      <Tag size="md" variant="transparent">
        <div className="comet-body-s-accented text-muted-slate">
          {count} experiments
        </div>
      </Tag>
    );
  }

  return null;
};

export default ExperimentTag;

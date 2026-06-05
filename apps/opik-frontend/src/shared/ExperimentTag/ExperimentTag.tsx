import React from "react";
import { FlaskConical } from "lucide-react";
import { Tag } from "@/ui/tag";

interface ExperimentTagProps {
  experimentName?: string;
  count?: number;
}

const ExperimentTag: React.FC<ExperimentTagProps> = ({
  experimentName,
  count,
}) => {
  if (experimentName) {
    return (
      <Tag size="md" variant="transparent" className="flex items-center gap-1">
        <FlaskConical
          className="size-3 shrink-0"
          style={{ color: "var(--color-burgundy)" }}
        />
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

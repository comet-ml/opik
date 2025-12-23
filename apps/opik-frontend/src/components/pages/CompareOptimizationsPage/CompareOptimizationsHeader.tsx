import React from "react";
import { Tag } from "@/components/ui/tag";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { STATUS_TO_VARIANT_MAP } from "@/constants/experiments";

type CompareOptimizationsHeaderProps = {
  title: string;
  status?: OPTIMIZATION_STATUS;
};

const CompareOptimizationsHeader: React.FC<CompareOptimizationsHeaderProps> = ({
  title,
  status,
}) => {
  return (
    <div className="mb-4 flex min-h-8 flex-nowrap items-center gap-2">
      <h1 className="comet-title-l truncate break-words">{title}</h1>
      {status && (
        <Tag
          variant={STATUS_TO_VARIANT_MAP[status]}
          size="md"
          className="capitalize"
        >
          {status}
        </Tag>
      )}
    </div>
  );
};

export default CompareOptimizationsHeader;

import React from "react";

import { FormLabel } from "@/components/ui/form";
import { Description } from "@/components/ui/description";
import ColumnsContent from "@/components/shared/ColumnsContent/ColumnsContent";
import type { ColumnsContentProps } from "@/components/shared/ColumnsContent/ColumnsContent";

type ColumnsSectionProps<TColumnData> = ColumnsContentProps<TColumnData> & {
  label?: string;
  description?: string;
};

const ColumnsSection = <TColumnData,>({
  label = "Columns",
  description,
  ...contentProps
}: ColumnsSectionProps<TColumnData>) => {
  return (
    <div className="space-y-2">
      <div className="space-y-1">
        <FormLabel>{label}</FormLabel>
        {description && (
          <Description className="block">{description}</Description>
        )}
      </div>
      <div className="rounded-md border border-border">
        <ColumnsContent {...contentProps} />
      </div>
    </div>
  );
};

export default ColumnsSection;

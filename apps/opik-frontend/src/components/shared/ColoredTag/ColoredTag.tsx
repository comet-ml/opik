import React, { useMemo } from "react";

import { Tag, TagProps } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

export interface ColoredTagProps {
  label: string;
  size?: TagProps["size"];
  testId?: string;
  className?: string;
  IconComponent?: React.ComponentType<{ className?: string }>;
}

const ColoredTag: React.FunctionComponent<ColoredTagProps> = ({
  label,
  size = "md",
  testId,
  className,
  IconComponent,
}) => {
  const variant = useMemo(() => generateTagVariant(label), [label]);

  return (
    <Tag
      size={size}
      variant={variant}
      data-testid={testId}
      className={className}
    >
      <TooltipWrapper content={label} stopClickPropagation>
        <div className="flex min-w-0 items-center gap-1">
          {IconComponent && <IconComponent className="size-3 shrink-0" />}
          <span className="truncate">{label}</span>
        </div>
      </TooltipWrapper>
    </Tag>
  );
};

export default ColoredTag;

import React, { useMemo } from "react";

import { Tag, TagProps } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

export interface ColoredTagProps {
  label: string;
  size?: TagProps["size"];
  testId?: string;
  className?: string;
}

const ColoredTag: React.FunctionComponent<ColoredTagProps> = ({
  label,
  size = "md",
  testId,
  className,
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
        <span>{label}</span>
      </TooltipWrapper>
    </Tag>
  );
};

export default ColoredTag;

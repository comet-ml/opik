import React from "react";
import { type LucideIcon } from "lucide-react";

import ResourceLink, {
  RESOURCE_TYPE,
  RESOURCE_MAP,
} from "@/shared/ResourceLink/ResourceLink";
import { type TagTextSize } from "@/ui/tag";
import { Filter } from "@/types/filters";

type NavigationTagProps = {
  id: string;
  name: string;
  resource: RESOURCE_TYPE;
  search?: Record<string, string | number | string[] | Filter[]>;
  tooltipContent?: string | false;
  className?: string;
  isSmall?: boolean;
  textSize?: TagTextSize;
  prefix?: string;
  suffix?: React.ReactNode;
  icon?: LucideIcon;
};

const NavigationTag: React.FunctionComponent<NavigationTagProps> = ({
  id,
  name,
  resource,
  search,
  tooltipContent,
  className,
  isSmall = false,
  textSize,
  prefix,
  suffix,
  icon,
}) => {
  const resourceLabel = RESOURCE_MAP[resource].label;
  const defaultTooltipContent = `Go to ${resourceLabel}: ${name}`;

  return (
    <ResourceLink
      id={id}
      name={name}
      resource={resource}
      search={search}
      tooltipContent={tooltipContent ?? defaultTooltipContent}
      className={className}
      asTag
      isSmall={isSmall}
      textSize={textSize}
      prefix={prefix}
      suffix={suffix}
      icon={icon}
    />
  );
};

export default NavigationTag;

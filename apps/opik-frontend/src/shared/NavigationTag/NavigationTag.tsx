import React from "react";

import ResourceLink, {
  RESOURCE_TYPE,
  RESOURCE_MAP,
} from "@/shared/ResourceLink/ResourceLink";
import { Filter } from "@/types/filters";

type NavigationTagProps = {
  id: string;
  name: string;
  resource: RESOURCE_TYPE;
  search?: Record<string, string | number | string[] | Filter[]>;
  tooltipContent?: string;
  className?: string;
  isSmall?: boolean;
  prefix?: string;
  suffix?: React.ReactNode;
};

const NavigationTag: React.FunctionComponent<NavigationTagProps> = ({
  id,
  name,
  resource,
  search,
  tooltipContent,
  className,
  isSmall = false,
  prefix,
  suffix,
}) => {
  const resourceLabel = RESOURCE_MAP[resource].label;
  const defaultTooltipContent = `Navigate to ${resourceLabel}: ${name}`;

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
      prefix={prefix}
      suffix={suffix}
    />
  );
};

export default NavigationTag;

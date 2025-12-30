import React from "react";

import ResourceLink, {
  RESOURCE_TYPE,
  RESOURCE_MAP,
} from "@/components/shared/ResourceLink/ResourceLink";
import { Filter } from "@/types/filters";

type NavigationTagProps = {
  id: string;
  name: string;
  resource: RESOURCE_TYPE;
  search?: Record<string, string | number | string[] | Filter[]>;
  tooltipContent?: string;
  className?: string;
};

const NavigationTag: React.FunctionComponent<NavigationTagProps> = ({
  id,
  name,
  resource,
  search,
  tooltipContent,
  className,
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
      variant="transparent"
      className={className}
      iconsSize={3}
      gapSize={1}
      asTag
    />
  );
};

export default NavigationTag;

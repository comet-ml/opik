import React from "react";

import ResourceLink, {
  RESOURCE_TYPE,
  RESOURCE_MAP,
} from "@/components/shared/ResourceLink/ResourceLink";
import { Filter } from "@/types/filters";
import { TagProps } from "@/components/ui/tag";

type NavigationTagProps = {
  id: string;
  name: string;
  resource: RESOURCE_TYPE;
  search?: Record<string, string | number | string[] | Filter[]>;
  tooltipContent?: string;
  size?: TagProps["size"];
  className?: string;
};

const NavigationTag: React.FunctionComponent<NavigationTagProps> = ({
  id,
  name,
  resource,
  search,
  tooltipContent,
  size,
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
      size={size}
      className={className}
      iconsSize={3}
      gapSize={1}
      asTag
    />
  );
};

export default NavigationTag;

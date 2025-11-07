import React from "react";
import ResourceLink, {
  RESOURCE_TYPE,
  RESOURCE_MAP,
} from "@/components/shared/ResourceLink/ResourceLink";

type NavigationTagProps = {
  id: string;
  name: string;
  resource: RESOURCE_TYPE;
};

const NavigationTag = ({ id, name, resource }: NavigationTagProps) => {
  const resourceLabel = RESOURCE_MAP[resource].label;
  const tooltipContent = `Navigate to ${resourceLabel}: ${name}`;

  return (
    <ResourceLink
      id={id}
      name={name}
      resource={resource}
      tooltipContent={tooltipContent}
      asTag
    />
  );
};

export default NavigationTag;

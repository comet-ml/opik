import React from "react";

import { ALERT_TYPE } from "@/types/alerts";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { ALERT_TYPE_LABELS, ALERT_TYPE_ICONS } from "./helpers";

interface DestinationSelectorProps {
  value: ALERT_TYPE;
  onChange: (value: ALERT_TYPE) => void;
}

const ALERT_TYPES = [
  ALERT_TYPE.general,
  ALERT_TYPE.slack,
  ALERT_TYPE.pagerduty,
] as const;

const DestinationSelector: React.FunctionComponent<
  DestinationSelectorProps
> = ({ value, onChange }) => {
  const handleValueChange = (newValue: string) => {
    if (newValue) {
      onChange(newValue as ALERT_TYPE);
    }
  };

  return (
    <ToggleGroup
      type="single"
      variant="ghost"
      value={value}
      onValueChange={handleValueChange}
      className="w-fit justify-start"
    >
      {ALERT_TYPES.map((alertType) => {
        const Icon = ALERT_TYPE_ICONS[alertType];
        const label = ALERT_TYPE_LABELS[alertType];

        return (
          <ToggleGroupItem
            key={alertType}
            value={alertType}
            aria-label={label}
            className="gap-1.5"
          >
            <Icon className="size-3.5" />
            <span>{label}</span>
          </ToggleGroupItem>
        );
      })}
    </ToggleGroup>
  );
};

export default DestinationSelector;

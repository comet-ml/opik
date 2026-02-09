import React from "react";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { LOGS_TYPE } from "@/constants/traces";

type LogsTypeToggleProps = {
  value: LOGS_TYPE;
  onValueChange: (value: LOGS_TYPE) => void;
};

const LogsTypeToggle: React.FC<LogsTypeToggleProps> = ({
  value,
  onValueChange,
}) => {
  return (
    <ToggleGroup
      type="single"
      value={value}
      onValueChange={(val) => val && onValueChange(val as LOGS_TYPE)}
      variant="secondary"
      className="w-fit"
    >
      <ToggleGroupItem value={LOGS_TYPE.threads} size="sm">
        Threads
      </ToggleGroupItem>
      <ToggleGroupItem value={LOGS_TYPE.traces} size="sm">
        Traces
      </ToggleGroupItem>
      <ToggleGroupItem value={LOGS_TYPE.spans} size="sm">
        Spans
      </ToggleGroupItem>
    </ToggleGroup>
  );
};

export default LogsTypeToggle;

import React from "react";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
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
      <ToggleGroupItem value={LOGS_TYPE.threads} size="xs">
        Threads
      </ToggleGroupItem>
      <ToggleGroupItem value={LOGS_TYPE.traces} size="xs">
        Traces
      </ToggleGroupItem>
      <ToggleGroupItem value={LOGS_TYPE.spans} size="xs">
        Spans
      </ToggleGroupItem>
    </ToggleGroup>
  );
};

export default LogsTypeToggle;

import React, { useCallback, useMemo } from "react";

import ExperimentsSelectBox from "@/v2/pages-shared/experiments/ExperimentsSelectBox/ExperimentsSelectBox";

type ExperimentsFilterSelectProps = {
  value: string;
  onValueChange: (value: string) => void;
  projectId: string;
  className?: string;
  disabled?: boolean;
};

const ExperimentsFilterSelect: React.FC<ExperimentsFilterSelectProps> = ({
  value,
  onValueChange,
  projectId,
  className,
  disabled,
}) => {
  const ids = useMemo(
    () => (value ? String(value).split(",").filter(Boolean) : []),
    [value],
  );

  const handleChange = useCallback(
    (next: string[]) => onValueChange(next.join(",")),
    [onValueChange],
  );

  return (
    <ExperimentsSelectBox
      multiselect
      value={ids}
      onValueChange={handleChange}
      projectId={projectId}
      className={className}
      disabled={disabled}
    />
  );
};

export default ExperimentsFilterSelect;

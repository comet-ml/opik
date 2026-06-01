import React, { useCallback, useMemo } from "react";

import ExperimentsSelectBox from "@/v2/pages-shared/experiments/ExperimentsSelectBox/ExperimentsSelectBox";

type ExperimentsSelectBoxFilterWrapperProps = {
  value: string;
  onValueChange: (value: string) => void;
  projectId: string;
  className?: string;
  disabled?: boolean;
};

const ExperimentsSelectBoxFilterWrapper: React.FC<
  ExperimentsSelectBoxFilterWrapperProps
> = ({ value, onValueChange, projectId, className, disabled }) => {
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

export default ExperimentsSelectBoxFilterWrapper;

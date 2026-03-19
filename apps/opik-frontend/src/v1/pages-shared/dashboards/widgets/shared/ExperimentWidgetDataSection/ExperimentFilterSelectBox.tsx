import React, { useCallback, useMemo } from "react";
import ExperimentsSelectBox from "@/v1/pages-shared/experiments/ExperimentsSelectBox/ExperimentsSelectBox";

interface ExperimentFilterSelectBoxProps {
  value: string;
  onValueChange: (value: string) => void;
  className?: string;
}

const ExperimentFilterSelectBox: React.FC<ExperimentFilterSelectBoxProps> = ({
  value,
  onValueChange,
  className,
}) => {
  const selectedIds = useMemo(
    () =>
      value
        ? value
            .split(",")
            .map((id) => id.trim())
            .filter(Boolean)
        : [],
    [value],
  );

  const handleChange = useCallback(
    (ids: string[]) => {
      onValueChange(ids.join(","));
    },
    [onValueChange],
  );

  return (
    <ExperimentsSelectBox
      value={selectedIds}
      onValueChange={handleChange}
      multiselect
      className={className}
    />
  );
};

export default ExperimentFilterSelectBox;

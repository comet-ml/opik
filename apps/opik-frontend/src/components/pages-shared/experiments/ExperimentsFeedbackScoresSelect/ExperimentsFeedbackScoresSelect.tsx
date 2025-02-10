import React, { useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import sortBy from "lodash/sortBy";

import useExperimentsFeedbackScoresNames from "@/api/datasets/useExperimentsFeedbackScoresNames";
import SelectBox from "@/components/shared/SelectBox/SelectBox";

type ExperimentsFeedbackScoresSelectProps = {
  experimentsIds?: string[];
  value: string;
  onValueChange: (value: string) => void;
  placeholder?: string;
};

const ExperimentsFeedbackScoresSelect: React.FC<
  ExperimentsFeedbackScoresSelectProps
> = ({
  experimentsIds,
  value,
  onValueChange,
  placeholder = "Select score",
}) => {
  const { data } = useExperimentsFeedbackScoresNames(
    {
      experimentsIds,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const options = useMemo(() => {
    return sortBy(
      (data?.scores || []).map((f) => ({ label: f.name, value: f.name })),
      "label",
    );
  }, [data]);

  return (
    <SelectBox
      value={value}
      onChange={onValueChange}
      options={options}
      placeholder={placeholder}
    />
  );
};

export default ExperimentsFeedbackScoresSelect;

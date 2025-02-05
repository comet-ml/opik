import React, { useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";

import Autocomplete from "@/components/shared/Autocomplete/Autocomplete";
import useExperimentsFeedbackScoresNames from "@/api/datasets/useExperimentsFeedbackScoresNames";

type TracesOrSpansFeedbackScoresAutocompleteProps = {
  experimentsIds?: string[];
  hasError?: boolean;
  value: string;
  onValueChange: (value: string) => void;
  placeholder?: string;
};

const ExperimentsFeedbackScoresAutocomplete: React.FC<
  TracesOrSpansFeedbackScoresAutocompleteProps
> = ({
  experimentsIds,
  hasError,
  value,
  onValueChange,
  placeholder = "Feedback score",
}) => {
  const { data, isPending } = useExperimentsFeedbackScoresNames(
    {
      experimentsIds,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const items = useMemo(() => {
    return (data?.scores || [])
      .map((f) => f.name)
      .filter((p) =>
        value ? p.toLowerCase().includes(value.toLowerCase()) : true,
      )
      .sort();
  }, [data, value]);

  return (
    <Autocomplete
      value={value}
      onValueChange={onValueChange}
      items={items}
      hasError={hasError}
      isLoading={isPending}
      placeholder={placeholder}
    />
  );
};

export default ExperimentsFeedbackScoresAutocomplete;

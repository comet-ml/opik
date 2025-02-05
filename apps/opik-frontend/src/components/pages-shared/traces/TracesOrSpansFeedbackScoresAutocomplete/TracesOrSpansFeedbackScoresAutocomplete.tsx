import React, { useMemo } from "react";

import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import Autocomplete from "@/components/shared/Autocomplete/Autocomplete";
import useTracesOrSpansScoresColumns from "@/hooks/useTracesOrSpansScoresColumns";

type TracesOrSpansFeedbackScoresAutocompleteProps = {
  projectId: string;
  hasError?: boolean;
  value: string;
  onValueChange: (value: string) => void;
  type?: TRACE_DATA_TYPE;
  placeholder?: string;
};

const TracesOrSpansFeedbackScoresAutocomplete: React.FC<
  TracesOrSpansFeedbackScoresAutocompleteProps
> = ({
  projectId,
  hasError,
  value,
  onValueChange,
  type = TRACE_DATA_TYPE.traces,
  placeholder = "Feedback score",
}) => {
  const { data, isPending } = useTracesOrSpansScoresColumns(
    {
      projectId,
      type: type as TRACE_DATA_TYPE,
    },
    {},
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

export default TracesOrSpansFeedbackScoresAutocomplete;

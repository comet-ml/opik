import React, { useMemo } from "react";
import sortBy from "lodash/sortBy";

import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import useTracesOrSpansScoresColumns from "@/hooks/useTracesOrSpansScoresColumns";
import SelectBox from "@/components/shared/SelectBox/SelectBox";

type TracesOrSpansFeedbackScoresSelectProps = {
  projectId: string;
  value: string;
  onValueChange: (value: string) => void;
  type?: TRACE_DATA_TYPE;
  placeholder?: string;
  className?: string;
};

const TracesOrSpansFeedbackScoresSelect: React.FC<
  TracesOrSpansFeedbackScoresSelectProps
> = ({
  projectId,
  value,
  onValueChange,
  type = TRACE_DATA_TYPE.traces,
  placeholder = "Select score",
  className,
}) => {
  const { data } = useTracesOrSpansScoresColumns(
    {
      projectId,
      type: type as TRACE_DATA_TYPE,
    },
    {},
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
      className={className}
    />
  );
};

export default TracesOrSpansFeedbackScoresSelect;

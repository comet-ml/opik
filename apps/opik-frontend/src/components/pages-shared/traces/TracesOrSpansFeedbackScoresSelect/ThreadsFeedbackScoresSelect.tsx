import React, { useMemo } from "react";
import sortBy from "lodash/sortBy";

import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import useThreadsFeedbackScoresNames from "@/api/traces/useThreadsFeedbackScoresNames";

type ThreadsFeedbackScoresSelectProps = {
  projectId: string;
  value: string;
  onValueChange: (value: string) => void;
  type?: TRACE_DATA_TYPE;
  placeholder?: string;
  className?: string;
};

const ThreadsFeedbackScoresSelect: React.FC<
  ThreadsFeedbackScoresSelectProps
> = ({
  projectId,
  value,
  onValueChange,
  placeholder = "Select score",
  className,
}) => {
  const { data: feedbackScoresNames } = useThreadsFeedbackScoresNames({
    projectId,
  });

  const options = useMemo(() => {
    return sortBy(
      (feedbackScoresNames?.scores || []).map((f) => ({
        label: f.name,
        value: f.name,
      })),
      "label",
    );
  }, [feedbackScoresNames]);

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

export default ThreadsFeedbackScoresSelect;

import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";

import useAppStore from "@/store/AppStore";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import { DropdownOption } from "@/types/shared";

const DEFAULT_LOADED_EXPERIMENT_ITEMS = 1000;

type ExperimentSelectBoxProps = {
  value: string;
  onValueChange: (value: string) => void;
  placeholder?: string;
  className?: string;
};

const ExperimentSelectBox: React.FC<ExperimentSelectBoxProps> = ({
  value,
  onValueChange,
  placeholder = "Select an experiment",
  className,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [isLoadedMore, setIsLoadedMore] = useState(false);
  const { data, isLoading } = useExperimentsList(
    {
      workspaceName,
      page: 1,
      size: isLoadedMore ? 10000 : DEFAULT_LOADED_EXPERIMENT_ITEMS,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const total = data?.total ?? 0;

  const loadMoreHandler = useCallback(() => setIsLoadedMore(true), []);

  const options: DropdownOption<string>[] = useMemo(() => {
    return (data?.content || []).map((experiment) => ({
      value: experiment.name,
      label: experiment.name,
    }));
  }, [data?.content]);

  return (
    <LoadableSelectBox
      options={options}
      value={value}
      placeholder={placeholder}
      onChange={onValueChange}
      onLoadMore={
        total > DEFAULT_LOADED_EXPERIMENT_ITEMS && !isLoadedMore
          ? loadMoreHandler
          : undefined
      }
      buttonClassName={className}
      isLoading={isLoading}
      optionsCount={DEFAULT_LOADED_EXPERIMENT_ITEMS}
    />
  );
};

export default ExperimentSelectBox;

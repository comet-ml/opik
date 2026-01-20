import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";

import useAppStore from "@/store/AppStore";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import { DropdownOption } from "@/types/shared";
import { Sorting } from "@/types/sorting";

const DEFAULT_LOADED_EXPERIMENT_ITEMS = 1000;
const DEFAULT_EXPERIMENTS_SORTING: Sorting = [{ id: "name", desc: false }];

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
      sorting: DEFAULT_EXPERIMENTS_SORTING,
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

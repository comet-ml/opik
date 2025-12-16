import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData, useQueryClient } from "@tanstack/react-query";

import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import { DropdownOption } from "@/types/shared";
import useAppStore from "@/store/AppStore";
import { Experiment } from "@/types/datasets";
import { Sorting } from "@/types/sorting";

const DEFAULT_LOADED_EXPERIMENT_ITEMS = 1000;
const MORE_LOADED_EXPERIMENT_ITEMS = 10000;
export const EXPERIMENTS_SELECT_QUERY_KEY = "experiments-select-box";

const DEFAULT_EXPERIMENTS_SORTING: Sorting = [{ id: "created_at", desc: true }];

export type UseExperimentsSelectDataParams = {
  isLoadedMore?: boolean;
  sorting?: Sorting;
};

export type UseExperimentsSelectDataResponse = {
  experiments: Experiment[];
  total: number;
  isLoading: boolean;
};

export const useExperimentsSelectData = ({
  isLoadedMore,
  sorting = DEFAULT_EXPERIMENTS_SORTING,
}: UseExperimentsSelectDataParams): UseExperimentsSelectDataResponse => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const queryClient = useQueryClient();

  const cachedQueries = queryClient.getQueryCache().findAll({
    queryKey: [EXPERIMENTS_SELECT_QUERY_KEY],
    exact: false,
  });

  type CachedExperimentsData = { content: Experiment[]; total: number };

  const longestCachedData = cachedQueries.reduce<CachedExperimentsData | null>(
    (longest, query) => {
      const data = query.state.data as CachedExperimentsData | undefined;
      if (
        data?.content &&
        (!longest || data.content.length > longest.content.length)
      ) {
        return data;
      }
      return longest;
    },
    null,
  );

  const shouldFetch = !longestCachedData || isLoadedMore;

  const { data: fetchedData, isLoading: isFetching } = useExperimentsList(
    {
      workspaceName,
      page: 1,
      size: isLoadedMore
        ? MORE_LOADED_EXPERIMENT_ITEMS
        : DEFAULT_LOADED_EXPERIMENT_ITEMS,
      sorting,
      queryKey: EXPERIMENTS_SELECT_QUERY_KEY,
    },
    {
      enabled: shouldFetch,
      placeholderData: keepPreviousData,
    },
  );

  if (longestCachedData) {
    return {
      experiments: longestCachedData.content,
      total: longestCachedData.total,
      isLoading: false,
    };
  }

  if (shouldFetch) {
    return {
      experiments: fetchedData?.content ?? [],
      total: fetchedData?.total ?? 0,
      isLoading: isFetching,
    };
  }

  return {
    experiments: [],
    total: 0,
    isLoading: false,
  };
};

interface BaseExperimentsSelectBoxProps {
  className?: string;
  disabled?: boolean;
  minWidth?: number;
  customOptions?: DropdownOption<string>[];
  sorting?: Sorting;
}

interface SingleSelectExperimentsProps extends BaseExperimentsSelectBoxProps {
  value: string;
  onValueChange: (value: string) => void;
  multiselect?: false;
}

interface MultiSelectExperimentsProps extends BaseExperimentsSelectBoxProps {
  value: string[];
  onValueChange: (value: string[]) => void;
  multiselect: true;
  showSelectAll?: boolean;
  selectAllLabel?: string;
}

type ExperimentsSelectBoxProps =
  | SingleSelectExperimentsProps
  | MultiSelectExperimentsProps;

const ExperimentsSelectBox: React.FC<ExperimentsSelectBoxProps> = (props) => {
  const { className, disabled, minWidth, customOptions, sorting } = props;
  const [isLoadedMore, setIsLoadedMore] = useState(false);

  const { experiments, total, isLoading } = useExperimentsSelectData({
    isLoadedMore,
    sorting,
  });

  const loadMoreHandler = useCallback(() => setIsLoadedMore(true), []);

  const options: DropdownOption<string>[] = useMemo(() => {
    const experimentOptions = experiments.map((experiment) => ({
      value: experiment.id,
      label: experiment.name,
    }));
    return customOptions
      ? [...customOptions, ...experimentOptions]
      : experimentOptions;
  }, [experiments, customOptions]);

  const loadableSelectBoxProps = props.multiselect
    ? {
        options,
        value: props.value,
        placeholder: "Select experiments",
        onChange: props.onValueChange,
        multiselect: true as const,
        showSelectAll: props.showSelectAll,
        selectAllLabel: props.selectAllLabel || "All experiments",
      }
    : {
        options,
        value: props.value,
        placeholder: "Select an experiment",
        onChange: props.onValueChange,
        multiselect: false as const,
      };

  return (
    <LoadableSelectBox
      {...loadableSelectBoxProps}
      onLoadMore={
        total > DEFAULT_LOADED_EXPERIMENT_ITEMS && !isLoadedMore
          ? loadMoreHandler
          : undefined
      }
      buttonClassName={className}
      minWidth={minWidth}
      disabled={disabled}
      isLoading={isLoading}
      optionsCount={DEFAULT_LOADED_EXPERIMENT_ITEMS}
    />
  );
};

export default ExperimentsSelectBox;

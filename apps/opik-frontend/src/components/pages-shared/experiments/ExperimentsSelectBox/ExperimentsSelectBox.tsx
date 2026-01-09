import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData, useQueryClient } from "@tanstack/react-query";

import { cn } from "@/lib/utils";
import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import SelectBoxClearWrapper from "@/components/shared/SelectBoxClearWrapper/SelectBoxClearWrapper";
import useExperimentsList, {
  UseExperimentsListParams,
} from "@/api/datasets/useExperimentsList";
import { DropdownOption } from "@/types/shared";
import useAppStore from "@/store/AppStore";
import { Experiment } from "@/types/datasets";
import { Sorting } from "@/types/sorting";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

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

  // Find cached queries that match the current workspace and sorting
  // to avoid returning stale data from different contexts
  const cachedQueries = queryClient.getQueryCache().findAll({
    queryKey: [EXPERIMENTS_SELECT_QUERY_KEY],
    exact: false,
    predicate: (query) => {
      const queryParams = query.queryKey[1] as
        | UseExperimentsListParams
        | undefined;
      // Only consider queries from the same workspace with matching sorting
      return (
        queryParams?.workspaceName === workspaceName &&
        JSON.stringify(queryParams?.sorting) === JSON.stringify(sorting)
      );
    },
  });

  type CachedExperimentsData = { content: Experiment[]; total: number };

  // Find the longest cached dataset to maximize available data without refetching.
  // If a user previously loaded 10,000 experiments via "Load more", we prefer
  // reusing that larger dataset rather than fetching only 1,000 again.
  // This improves UX by showing more options immediately from cache.
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
  showClearButton?: boolean;
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
  const {
    className,
    disabled,
    minWidth,
    customOptions,
    sorting,
    showClearButton = false,
  } = props;
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

  const renderExperimentsTitle = useCallback(
    (selectedOptions: DropdownOption<string>[]) => {
      const count = selectedOptions.length;
      if (count === 1) {
        return <div className="truncate">{selectedOptions[0].label}</div>;
      }
      const experimentNames = selectedOptions.map((o) => o.label).join(", ");
      return (
        <TooltipWrapper content={experimentNames}>
          <div className="truncate">{count} experiments</div>
        </TooltipWrapper>
      );
    },
    [],
  );

  const loadableSelectBoxProps = props.multiselect
    ? {
        options,
        value: props.value,
        placeholder: "Select experiments",
        onChange: props.onValueChange,
        multiselect: true as const,
        showSelectAll: props.showSelectAll,
        selectAllLabel: props.selectAllLabel || "All experiments",
        renderTitle: renderExperimentsTitle,
      }
    : {
        options,
        value: props.value,
        placeholder: "Select an experiment",
        onChange: props.onValueChange,
        multiselect: false as const,
      };

  const isClearable =
    showClearButton &&
    (props.multiselect ? props.value.length > 0 : Boolean(props.value));

  const handleClear = useCallback(() => {
    if (props.multiselect) {
      props.onValueChange([]);
    } else {
      props.onValueChange("");
    }
  }, [props]);

  const selectBox = (
    <LoadableSelectBox
      {...loadableSelectBoxProps}
      onLoadMore={
        total > DEFAULT_LOADED_EXPERIMENT_ITEMS && !isLoadedMore
          ? loadMoreHandler
          : undefined
      }
      buttonClassName={cn(className, {
        "rounded-r-none": isClearable,
      })}
      minWidth={minWidth}
      disabled={disabled}
      isLoading={isLoading}
      optionsCount={DEFAULT_LOADED_EXPERIMENT_ITEMS}
    />
  );

  if (!showClearButton) {
    return selectBox;
  }

  return (
    <SelectBoxClearWrapper
      isClearable={isClearable}
      onClear={handleClear}
      disabled={disabled}
      clearTooltip="Clear experiment selection"
      buttonSize="icon"
    >
      {selectBox}
    </SelectBoxClearWrapper>
  );
};

export default ExperimentsSelectBox;

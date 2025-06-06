import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";

import useAppStore from "@/store/AppStore";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import { DropdownOption } from "@/types/shared";

const DEFAULT_LOADED_DATASET_ITEMS = 1000;

type DatasetSelectBoxProps = {
  value: string;
  onValueChange: (value: string) => void;
  placeholder?: string;
  className?: string;
};

const DatasetSelectBox: React.FC<DatasetSelectBoxProps> = ({
  value,
  onValueChange,
  placeholder = "Select a dataset",
  className,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [isLoadedMore, setIsLoadedMore] = useState(false);
  const { data, isLoading } = useDatasetsList(
    {
      workspaceName,
      page: 1,
      size: isLoadedMore ? 10000 : DEFAULT_LOADED_DATASET_ITEMS,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const total = data?.total ?? 0;

  const loadMoreHandler = useCallback(() => setIsLoadedMore(true), []);

  const options: DropdownOption<string>[] = useMemo(() => {
    return (data?.content || []).map((dataset) => ({
      value: dataset.id,
      label: dataset.name,
    }));
  }, [data?.content]);

  return (
    <LoadableSelectBox
      options={options}
      value={value}
      placeholder={placeholder}
      onChange={onValueChange}
      onLoadMore={
        total > DEFAULT_LOADED_DATASET_ITEMS && !isLoadedMore
          ? loadMoreHandler
          : undefined
      }
      buttonClassName={className}
      isLoading={isLoading}
      optionsCount={DEFAULT_LOADED_DATASET_ITEMS}
    />
  );
};

export default DatasetSelectBox;

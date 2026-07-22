import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { Database } from "lucide-react";

import useProjectDatasetsList from "@/api/datasets/useProjectDatasetsList";
import LoadableSelectBox from "@/shared/LoadableSelectBox/LoadableSelectBox";
import { DropdownOption } from "@/types/shared";
import { usePermissions } from "@/contexts/PermissionsContext";

const DEFAULT_LOADED_DATASET_ITEMS = 1000;

/** Renders the selected value with the gold dataset icon (matches Figma). */
const renderDatasetTitleWithIcon = (option: DropdownOption<string>) => (
  <div className="flex min-w-0 items-center gap-2">
    <Database className="size-4 shrink-0 text-[color:var(--chart-yellow)]" />
    <span className="truncate">{option.label}</span>
  </div>
);

type DatasetSelectBoxProps = {
  value: string;
  onValueChange: (value: string) => void;
  projectId?: string | null;
  placeholder?: string;
  className?: string;
  /** Show the gold dataset icon next to the selected value (matches Figma). */
  showIcon?: boolean;
  disabled?: boolean;
};

const DatasetSelectBox: React.FC<DatasetSelectBoxProps> = ({
  value,
  onValueChange,
  projectId,
  placeholder = "Select a test suite",
  className,
  showIcon = false,
  disabled,
}) => {
  const {
    permissions: { canViewDatasets },
  } = usePermissions();
  const [isLoadedMore, setIsLoadedMore] = useState(false);
  const { data, isLoading } = useProjectDatasetsList(
    {
      projectId: projectId!,
      page: 1,
      size: isLoadedMore ? 10000 : DEFAULT_LOADED_DATASET_ITEMS,
    },
    {
      placeholderData: keepPreviousData,
      enabled: canViewDatasets && !!projectId,
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
      renderTitle={showIcon ? renderDatasetTitleWithIcon : undefined}
      onLoadMore={
        total > DEFAULT_LOADED_DATASET_ITEMS && !isLoadedMore
          ? loadMoreHandler
          : undefined
      }
      buttonClassName={className}
      isLoading={isLoading}
      disabled={disabled}
      optionsCount={DEFAULT_LOADED_DATASET_ITEMS}
    />
  );
};

export default DatasetSelectBox;

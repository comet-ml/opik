import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData, useQueryClient } from "@tanstack/react-query";

import { cn } from "@/lib/utils";
import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import SelectBoxClearWrapper from "@/components/shared/SelectBoxClearWrapper/SelectBoxClearWrapper";
import useProjectsList from "@/api/projects/useProjectsList";
import { DropdownOption } from "@/types/shared";
import useAppStore from "@/store/AppStore";
import { Project } from "@/types/projects";

const DEFAULT_LOADED_PROJECT_ITEMS = 1000;
const MORE_LOADED_PROJECT_ITEMS = 10000;
export const PROJECTS_SELECT_QUERY_KEY = "projects-select-box";

export type UseProjectsSelectDataParams = {
  isLoadedMore?: boolean;
};

export type UseProjectsSelectDataResponse = {
  projects: Project[];
  total: number;
  isLoading: boolean;
};

export const useProjectsSelectData = ({
  isLoadedMore,
}: UseProjectsSelectDataParams): UseProjectsSelectDataResponse => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const queryClient = useQueryClient();

  const cachedQueries = queryClient.getQueryCache().findAll({
    queryKey: [PROJECTS_SELECT_QUERY_KEY],
    exact: false,
  });

  type CachedProjectsData = { content: Project[]; total: number };

  const longestCachedData = cachedQueries.reduce<CachedProjectsData | null>(
    (longest, query) => {
      const data = query.state.data as CachedProjectsData | undefined;
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

  const { data: fetchedData, isLoading: isFetching } = useProjectsList(
    {
      workspaceName,
      page: 1,
      size: isLoadedMore
        ? MORE_LOADED_PROJECT_ITEMS
        : DEFAULT_LOADED_PROJECT_ITEMS,
      queryKey: PROJECTS_SELECT_QUERY_KEY,
    },
    {
      enabled: shouldFetch,
      placeholderData: keepPreviousData,
    },
  );

  if (longestCachedData) {
    return {
      projects: longestCachedData.content,
      total: longestCachedData.total,
      isLoading: false,
    };
  }

  if (shouldFetch) {
    return {
      projects: fetchedData?.content ?? [],
      total: fetchedData?.total ?? 0,
      isLoading: isFetching,
    };
  }

  return {
    projects: [],
    total: 0,
    isLoading: false,
  };
};

interface BaseProjectsSelectBoxProps {
  className?: string;
  disabled?: boolean;
  minWidth?: number;
  customOptions?: DropdownOption<string>[];
  align?: "start" | "end" | "center";
  showClearButton?: boolean;
}

interface SingleSelectProjectsProps extends BaseProjectsSelectBoxProps {
  value: string;
  onValueChange: (value: string) => void;
  multiselect?: false;
}

interface MultiSelectProjectsProps extends BaseProjectsSelectBoxProps {
  value: string[];
  onValueChange: (value: string[]) => void;
  multiselect: true;
  showSelectAll?: boolean;
  selectAllLabel?: string;
}

type ProjectsSelectBoxProps =
  | SingleSelectProjectsProps
  | MultiSelectProjectsProps;

const ProjectsSelectBox: React.FC<ProjectsSelectBoxProps> = (props) => {
  const {
    className,
    disabled,
    minWidth,
    customOptions,
    align,
    showClearButton = false,
  } = props;
  const [isLoadedMore, setIsLoadedMore] = useState(false);

  const { projects, total, isLoading } = useProjectsSelectData({
    isLoadedMore,
  });

  const loadMoreHandler = useCallback(() => setIsLoadedMore(true), []);

  const options: DropdownOption<string>[] = useMemo(() => {
    const projectOptions = projects.map((project) => ({
      value: project.id,
      label: project.name,
    }));
    return customOptions
      ? [...customOptions, ...projectOptions]
      : projectOptions;
  }, [projects, customOptions]);

  const loadableSelectBoxProps = props.multiselect
    ? {
        options,
        value: props.value,
        placeholder: "Select projects",
        onChange: props.onValueChange,
        multiselect: true as const,
        showSelectAll: props.showSelectAll,
        selectAllLabel: props.selectAllLabel || "All projects",
      }
    : {
        options,
        value: props.value,
        placeholder: "Select a project",
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
        total > DEFAULT_LOADED_PROJECT_ITEMS && !isLoadedMore
          ? loadMoreHandler
          : undefined
      }
      buttonClassName={cn(className, {
        "rounded-r-none": isClearable,
      })}
      minWidth={minWidth}
      align={align}
      disabled={disabled}
      isLoading={isLoading}
      optionsCount={DEFAULT_LOADED_PROJECT_ITEMS}
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
      clearTooltip="Clear project selection"
      buttonSize="icon"
    >
      {selectBox}
    </SelectBoxClearWrapper>
  );
};

export default ProjectsSelectBox;

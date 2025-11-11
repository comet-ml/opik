import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
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
  const { t } = useTranslation();
  const { className, disabled, minWidth } = props;
  const [isLoadedMore, setIsLoadedMore] = useState(false);

  const { projects, total, isLoading } = useProjectsSelectData({
    isLoadedMore,
  });

  const loadMoreHandler = useCallback(() => setIsLoadedMore(true), []);

  const options: DropdownOption<string>[] = useMemo(() => {
    return projects.map((project) => ({
      value: project.id,
      label: project.name,
    }));
  }, [projects]);

  const loadableSelectBoxProps = props.multiselect
    ? {
        options,
        value: props.value,
        placeholder: t("annotationQueues.dialog.selectProjects"),
        onChange: props.onValueChange,
        multiselect: true as const,
        showSelectAll: props.showSelectAll,
        selectAllLabel: props.selectAllLabel || t("annotationQueues.dialog.allProjects"),
      }
    : {
        options,
        value: props.value,
        placeholder: t("annotationQueues.dialog.selectProject"),
        onChange: props.onValueChange,
        multiselect: false as const,
      };

  return (
    <LoadableSelectBox
      {...loadableSelectBoxProps}
      onLoadMore={
        total > DEFAULT_LOADED_PROJECT_ITEMS && !isLoadedMore
          ? loadMoreHandler
          : undefined
      }
      buttonClassName={className}
      minWidth={minWidth}
      disabled={disabled}
      isLoading={isLoading}
      optionsCount={DEFAULT_LOADED_PROJECT_ITEMS}
    />
  );
};

export default ProjectsSelectBox;

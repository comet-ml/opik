import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";

import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import useProjectsList from "@/api/projects/useProjectsList";
import { DropdownOption } from "@/types/shared";
import useAppStore from "@/store/AppStore";

const DEFAULT_LOADED_PROJECT_ITEMS = 25;

type ProjectsSelectBoxProps = {
  value: string;
  onChange: (value: string) => void;
  className?: string;
};

const ProjectsSelectBox: React.FC<ProjectsSelectBoxProps> = ({
  value,
  onChange,
  className,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [isLoadedMore, setIsLoadedMore] = useState(false);

  const { data, isLoading } = useProjectsList(
    {
      workspaceName,
      page: 1,
      size: isLoadedMore ? 10000 : DEFAULT_LOADED_PROJECT_ITEMS,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const total = data?.total ?? 0;

  const loadMoreHandler = useCallback(() => setIsLoadedMore(true), []);

  const options: DropdownOption<string>[] = useMemo(() => {
    return (data?.content || []).map((project) => ({
      value: project.id,
      label: project.name,
    }));
  }, [data?.content]);

  return (
    <LoadableSelectBox
      options={options}
      value={value}
      placeholder="Select a project"
      onChange={onChange}
      onLoadMore={
        total > DEFAULT_LOADED_PROJECT_ITEMS && !isLoadedMore
          ? loadMoreHandler
          : undefined
      }
      buttonClassName={className}
      isLoading={isLoading}
      optionsCount={DEFAULT_LOADED_PROJECT_ITEMS}
    />
  );
};

export default ProjectsSelectBox;

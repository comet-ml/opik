import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";

import useAppStore from "@/store/AppStore";
import useProjectsList from "@/api/projects/useProjectsList";
import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import { DropdownOption } from "@/types/shared";

const DEFAULT_LOADED_PROJECT_ITEMS = 1000;

type ProjectSelectBoxProps = {
  value: string;
  onValueChange: (value: string) => void;
  placeholder?: string;
  className?: string;
};

const ProjectSelectBox: React.FC<ProjectSelectBoxProps> = ({
  value,
  onValueChange,
  placeholder = "Select a project",
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
      placeholder={placeholder}
      onChange={onValueChange}
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

export default ProjectSelectBox;

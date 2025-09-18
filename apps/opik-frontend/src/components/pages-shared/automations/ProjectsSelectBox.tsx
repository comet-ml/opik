import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";

import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import useProjectsList from "@/api/projects/useProjectsList";
import { DropdownOption } from "@/types/shared";
import useAppStore from "@/store/AppStore";

const DEFAULT_LOADED_PROJECT_ITEMS = 1000;

interface BaseProjectsSelectBoxProps {
  className?: string;
  disabled?: boolean;
}

interface SingleSelectProjectsProps extends BaseProjectsSelectBoxProps {
  value: string;
  onChange: (value: string) => void;
  multiselect?: false;
}

interface MultiSelectProjectsProps extends BaseProjectsSelectBoxProps {
  value: string[];
  onChange: (value: string[]) => void;
  multiselect: true;
}

type ProjectsSelectBoxProps =
  | SingleSelectProjectsProps
  | MultiSelectProjectsProps;

const ProjectsSelectBox: React.FC<ProjectsSelectBoxProps> = (props) => {
  const { className, disabled } = props;
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

  const loadableSelectBoxProps = props.multiselect
    ? {
        options,
        value: props.value,
        placeholder: "Select projects",
        onChange: props.onChange,
        multiselect: true as const,
      }
    : {
        options,
        value: props.value,
        placeholder: "Select a project",
        onChange: props.onChange,
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
      disabled={disabled}
      isLoading={isLoading}
      optionsCount={DEFAULT_LOADED_PROJECT_ITEMS}
    />
  );
};

export default ProjectsSelectBox;

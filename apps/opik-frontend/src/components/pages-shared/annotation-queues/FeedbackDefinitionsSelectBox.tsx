import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ExternalLink } from "lucide-react";

import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import { DropdownOption } from "@/types/shared";
import { FeedbackDefinition } from "@/types/feedback-definitions";
import useAppStore from "@/store/AppStore";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";

const DEFAULT_LOADED_FEEDBACK_DEFINITION_ITEMS = 1000;

interface BaseFeedbackDefinitionsSelectBoxProps {
  className?: string;
  disabled?: boolean;
  valueField?: keyof FeedbackDefinition;
}

interface SingleSelectFeedbackDefinitionsProps
  extends BaseFeedbackDefinitionsSelectBoxProps {
  value: string;
  onChange: (value: string) => void;
  multiselect?: false;
}

interface MultiSelectFeedbackDefinitionsProps
  extends BaseFeedbackDefinitionsSelectBoxProps {
  value: string[];
  onChange: (value: string[]) => void;
  multiselect: true;
  showSelectAll?: boolean;
}

type FeedbackDefinitionsSelectBoxProps =
  | SingleSelectFeedbackDefinitionsProps
  | MultiSelectFeedbackDefinitionsProps;

const FeedbackDefinitionsSelectBox: React.FC<
  FeedbackDefinitionsSelectBoxProps
> = (props) => {
  const { className, disabled, valueField = "id" } = props;
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [isLoadedMore, setIsLoadedMore] = useState(false);

  const { data, isLoading } = useFeedbackDefinitionsList(
    {
      workspaceName,
      page: 1,
      size: isLoadedMore ? 10000 : DEFAULT_LOADED_FEEDBACK_DEFINITION_ITEMS,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const total = data?.total ?? 0;

  const loadMoreHandler = useCallback(() => setIsLoadedMore(true), []);

  const options: DropdownOption<string>[] = useMemo(() => {
    return (data?.content || []).map((feedbackDefinition) => ({
      value: String(feedbackDefinition[valueField]),
      label: feedbackDefinition.name,
      description: feedbackDefinition.description,
    }));
  }, [data?.content, valueField]);

  const loadableSelectBoxProps = props.multiselect
    ? {
        options,
        value: props.value,
        placeholder: "Select feedback definitions",
        onChange: props.onChange,
        multiselect: true as const,
        showSelectAll: props.showSelectAll,
      }
    : {
        options,
        value: props.value,
        placeholder: "Select a feedback definition",
        onChange: props.onChange,
        multiselect: false as const,
      };

  const actionPanel = useMemo(
    () => (
      <div className="px-0.5">
        <Separator className="my-1" />
        <Button
          variant="link"
          className="w-full justify-start gap-1 px-2"
          asChild
        >
          <Link
            to="/$workspaceName/configuration"
            params={{ workspaceName }}
            search={{ tab: "feedback-definitions" }}
            target="_blank"
            rel="noopener noreferrer"
            className="flex items-center gap-1"
          >
            <span>Add new feedback definition</span>
            <ExternalLink className="size-3" />
          </Link>
        </Button>
      </div>
    ),
    [workspaceName],
  );

  return (
    <LoadableSelectBox
      {...loadableSelectBoxProps}
      actionPanel={actionPanel}
      onLoadMore={
        total > DEFAULT_LOADED_FEEDBACK_DEFINITION_ITEMS && !isLoadedMore
          ? loadMoreHandler
          : undefined
      }
      buttonClassName={className}
      disabled={disabled}
      isLoading={isLoading}
      optionsCount={DEFAULT_LOADED_FEEDBACK_DEFINITION_ITEMS}
    />
  );
};

export default FeedbackDefinitionsSelectBox;

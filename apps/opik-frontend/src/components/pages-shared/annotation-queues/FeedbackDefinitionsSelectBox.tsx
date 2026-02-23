import React, {
  useCallback,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { Plus } from "lucide-react";

import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import { DropdownOption } from "@/types/shared";
import {
  CreateFeedbackDefinition,
  FeedbackDefinition,
} from "@/types/feedback-definitions";
import useAppStore from "@/store/AppStore";
import { ListAction } from "@/components/ui/list-action";
import { Separator } from "@/components/ui/separator";
import AddEditFeedbackDefinitionDialog from "@/components/shared/AddEditFeedbackDefinitionDialog/AddEditFeedbackDefinitionDialog";

const DEFAULT_LOADED_FEEDBACK_DEFINITION_ITEMS = 1000;

interface BaseFeedbackDefinitionsSelectBoxProps {
  className?: string;
  disabled?: boolean;
  valueField?: keyof FeedbackDefinition;
  onInnerDialogOpenChange?: (open: boolean) => void;
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
  const {
    className,
    disabled,
    valueField = "id",
    onInnerDialogOpenChange,
  } = props;
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [isLoadedMore, setIsLoadedMore] = useState(false);
  const [feedbackDefinitionDialogOpen, setFeedbackDefinitionDialogOpen] =
    useState(false);
  const dialogKeyRef = useRef(0);

  const handleAddNewClick = useCallback(() => {
    dialogKeyRef.current = dialogKeyRef.current + 1;
    setFeedbackDefinitionDialogOpen(true);
  }, []);

  const handleFeedbackDefinitionCreated = (
    feedbackDefinition: CreateFeedbackDefinition,
  ) => {
    const newValue = String(
      feedbackDefinition[valueField as keyof CreateFeedbackDefinition],
    );

    if (props.multiselect) {
      props.onChange([...props.value, newValue]);
    } else {
      props.onChange(newValue);
    }
  };

  useLayoutEffect(() => {
    onInnerDialogOpenChange?.(feedbackDefinitionDialogOpen);
  }, [feedbackDefinitionDialogOpen, onInnerDialogOpenChange]);

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
    return (data?.content || [])
      .map((feedbackDefinition) => ({
        value: String(feedbackDefinition[valueField]),
        label: feedbackDefinition.name,
        description: feedbackDefinition.description,
      }))
      .sort((a, b) => a.label.localeCompare(b.label));
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
      <>
        <Separator className="my-1" />
        <ListAction onClick={handleAddNewClick}>
          <Plus className="size-3.5 shrink-0" />
          Add new
        </ListAction>
      </>
    ),
    [handleAddNewClick],
  );

  return (
    <>
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
      <AddEditFeedbackDefinitionDialog
        key={dialogKeyRef.current}
        open={feedbackDefinitionDialogOpen}
        setOpen={setFeedbackDefinitionDialogOpen}
        onCreated={handleFeedbackDefinitionCreated}
      />
    </>
  );
};

export default FeedbackDefinitionsSelectBox;

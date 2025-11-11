import React, { useMemo } from "react";
import { AnnotationQueue } from "@/types/annotation-queues";
import {
  FeedbackDefinition,
  FEEDBACK_DEFINITION_TYPE,
} from "@/types/feedback-definitions";
import { COLUMN_TYPE, ColumnData, ROW_HEIGHT } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import DataTable from "@/components/shared/DataTable/DataTable";
import FeedbackDefinitionsValueCell from "@/components/shared/DataTableCells/FeedbackDefinitionsValueCell";
import FeedbackScoreNameCell from "@/components/shared/DataTableCells/FeedbackScoreNameCell";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useAppStore from "@/store/AppStore";

interface ScoresContentProps {
  annotationQueue: AnnotationQueue;
}

export const DEFAULT_COLUMNS: ColumnData<FeedbackDefinition>[] = [
  {
    id: "name",
    label: "Feedback options",
    type: COLUMN_TYPE.numberDictionary,
    cell: FeedbackScoreNameCell as never,
  },
  {
    id: "description",
    label: "Description",
    type: COLUMN_TYPE.string,
  },
  {
    id: "values",
    label: "Available values",
    type: COLUMN_TYPE.string,
    cell: FeedbackDefinitionsValueCell as never,
  },
];

const ScoresContent: React.FunctionComponent<ScoresContentProps> = ({
  annotationQueue,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data } = useFeedbackDefinitionsList(
    {
      workspaceName,
      page: 1,
      size: 1000,
    },
    {
      enabled: annotationQueue.feedback_definition_names.length > 0,
    },
  );

  const feedbackDefinitions = useMemo(() => {
    const definitions: FeedbackDefinition[] = [];

    // Add selected feedback definitions
    if (data?.content && annotationQueue.feedback_definition_names.length > 0) {
      const selectedDefinitions = data.content.filter((def) =>
        annotationQueue.feedback_definition_names.includes(def.name),
      );
      definitions.push(...selectedDefinitions);
    }

    // Add Comments as the last row when comments are enabled
    if (annotationQueue.comments_enabled) {
      definitions.push({
        id: "comments",
        name: "Comments",
        description: "Free-text feedback",
        type: FEEDBACK_DEFINITION_TYPE.categorical,
        details: {
          categories: {},
        },
        created_at: "",
        last_updated_at: "",
      });
    }

    return definitions;
  }, [
    data?.content,
    annotationQueue.feedback_definition_names,
    annotationQueue.comments_enabled,
  ]);

  const columns = useMemo(
    () =>
      convertColumnDataToColumn<FeedbackDefinition, FeedbackDefinition>(
        DEFAULT_COLUMNS,
        {},
      ),
    [],
  );

  // Only hide the table if comments are disabled AND there are no feedback definitions
  if (
    !annotationQueue.comments_enabled &&
    !annotationQueue.feedback_definition_names.length
  ) {
    return null;
  }

  return (
    <DataTable
      columns={columns}
      data={feedbackDefinitions}
      rowHeight={ROW_HEIGHT.small}
      getRowId={(row) => row.id}
    />
  );
};

export default ScoresContent;

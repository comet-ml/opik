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
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useAppStore from "@/store/AppStore";
import FeedbackOptionCell from "./FeedbackOptionCell";

interface ScoresContentProps {
  annotationQueue: AnnotationQueue;
}

export const DEFAULT_COLUMNS: ColumnData<FeedbackDefinition>[] = [
  {
    id: "name",
    label: "Feedback option",
    type: COLUMN_TYPE.numberDictionary,
    cell: FeedbackOptionCell as never,
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

  // Extract repeated checks into well-named variables
  const hasComments = annotationQueue.comments_enabled;
  const hasFeedbackDefinitions =
    annotationQueue.feedback_definition_names.length > 0;
  const hasOnlyComments = hasComments && !hasFeedbackDefinitions;

  const { data } = useFeedbackDefinitionsList(
    {
      workspaceName,
      page: 1,
      size: 1000,
    },
    {
      enabled: hasFeedbackDefinitions,
    },
  );

  const feedbackDefinitions = useMemo(() => {
    const definitions: FeedbackDefinition[] = [];

    // Add selected feedback definitions
    if (data?.content && hasFeedbackDefinitions) {
      const selectedDefinitions = data.content.filter((def) =>
        annotationQueue.feedback_definition_names.includes(def.name),
      );
      definitions.push(...selectedDefinitions);
    }

    // Add Comments as the last row when comments are enabled
    if (hasComments) {
      definitions.push({
        id: "comments",
        name: "Comments",
        description: "Text field for open feedback or additional notes.",
        type: FEEDBACK_DEFINITION_TYPE.categorical,
        created_at: "",
        last_updated_at: "",
        details: {
          categories: {},
        },
      });
    }

    return definitions;
  }, [data?.content, hasFeedbackDefinitions, hasComments, annotationQueue]);

  const columns = useMemo(() => {
    // If only Comments row is shown, hide "Available values" column
    const columnsToShow = hasOnlyComments
      ? DEFAULT_COLUMNS.filter((col) => col.id !== "values")
      : DEFAULT_COLUMNS;

    return convertColumnDataToColumn<FeedbackDefinition, FeedbackDefinition>(
      columnsToShow,
      {},
    );
  }, [hasOnlyComments]);

  // Only hide the table if comments are disabled AND there are no feedback definitions
  if (!hasComments && !hasFeedbackDefinitions) {
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

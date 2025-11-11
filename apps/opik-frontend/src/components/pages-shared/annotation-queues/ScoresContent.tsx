import React, { useMemo } from "react";
import { CellContext } from "@tanstack/react-table";
import { AnnotationQueue } from "@/types/annotation-queues";
import {
  FeedbackDefinition,
  FEEDBACK_DEFINITION_TYPE,
} from "@/types/feedback-definitions";
import { COLUMN_TYPE, ColumnData, ROW_HEIGHT } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import DataTable from "@/components/shared/DataTable/DataTable";
import FeedbackDefinitionsValueCell from "@/components/shared/DataTableCells/FeedbackDefinitionsValueCell";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import ColoredTagNew from "@/components/shared/ColoredTag/ColoredTagNew";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useAppStore from "@/store/AppStore";

interface ScoresContentProps {
  annotationQueue: AnnotationQueue;
}

// Custom cell renderer for feedback option names
// Comments row is rendered without color, while other feedback definitions have colors
const FeedbackOptionNameCell = (context: CellContext<unknown, string>) => {
  const value = context.getValue();

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1.5"
    >
      {value === "Comments" ? (
        <span className="comet-body-s">{value}</span>
      ) : (
        <ColoredTagNew label={value} className="px-0" />
      )}
    </CellWrapper>
  );
};

export const DEFAULT_COLUMNS: ColumnData<FeedbackDefinition>[] = [
  {
    id: "name",
    label: "Feedback options",
    type: COLUMN_TYPE.numberDictionary,
    cell: FeedbackOptionNameCell as never,
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
  }, [
    data?.content,
    annotationQueue.feedback_definition_names,
    annotationQueue.comments_enabled,
  ]);

  const columns = useMemo(() => {
    // If only Comments row is shown (no feedback definitions), hide "Available values" column
    const hasOnlyComments =
      annotationQueue.comments_enabled &&
      annotationQueue.feedback_definition_names.length === 0;

    const columnsToShow = hasOnlyComments
      ? DEFAULT_COLUMNS.filter((col) => col.id !== "values")
      : DEFAULT_COLUMNS;

    return convertColumnDataToColumn<FeedbackDefinition, FeedbackDefinition>(
      columnsToShow,
      {},
    );
  }, [
    annotationQueue.comments_enabled,
    annotationQueue.feedback_definition_names.length,
  ]);

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

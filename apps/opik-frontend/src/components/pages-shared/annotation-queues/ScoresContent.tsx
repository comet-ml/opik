import React, { useMemo } from "react";
import { AnnotationQueue } from "@/types/annotation-queues";
import { FeedbackDefinition } from "@/types/feedback-definitions";
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
    label: "Feedback score name",
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
    if (!data?.content || !annotationQueue.feedback_definition_names.length) {
      return [];
    }

    return data.content.filter((def) =>
      annotationQueue.feedback_definition_names.includes(def.name),
    );
  }, [data?.content, annotationQueue.feedback_definition_names]);

  const columns = useMemo(
    () =>
      convertColumnDataToColumn<FeedbackDefinition, FeedbackDefinition>(
        DEFAULT_COLUMNS,
        {},
      ),
    [],
  );

  if (!annotationQueue.feedback_definition_names.length) {
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

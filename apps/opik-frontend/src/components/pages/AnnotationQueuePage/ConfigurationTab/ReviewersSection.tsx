import React, { useMemo } from "react";
import { AnnotationQueue } from "@/types/annotation-queues";
import { ROW_HEIGHT, COLUMN_TYPE, ColumnData } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import DataTable from "@/components/shared/DataTable/DataTable";

interface ReviewerRowData {
  username: string;
  progress: string;
}

interface ReviewersSectionProps {
  annotationQueue: AnnotationQueue;
}

export const DEFAULT_COLUMNS: ColumnData<ReviewerRowData>[] = [
  {
    id: "username",
    label: "Reviewer",
    type: COLUMN_TYPE.string,
  },
  {
    id: "progress",
    label: "Progress",
    type: COLUMN_TYPE.string,
  },
];

const ReviewersSection: React.FunctionComponent<ReviewersSectionProps> = ({
  annotationQueue,
}) => {
  const reviewerColumns = useMemo(
    () =>
      convertColumnDataToColumn<ReviewerRowData, ReviewerRowData>(
        DEFAULT_COLUMNS,
        {},
      ),
    [],
  );

  const rows = useMemo(() => {
    if (!annotationQueue?.reviewers) return [];

    return annotationQueue.reviewers.map<ReviewerRowData>((reviewer) => ({
      username: reviewer.username,
      progress: `${reviewer.status}/${annotationQueue.items_count}`,
    }));
  }, [annotationQueue?.reviewers, annotationQueue?.items_count]);

  if (!annotationQueue.reviewers || annotationQueue.reviewers.length === 0) {
    return null;
  }

  return (
    <div className="pt-6">
      <h2 className="comet-title-s truncate break-words bg-soft-background pb-3 pt-2">
        Reviewers ({annotationQueue.reviewers.length})
      </h2>
      <DataTable
        columns={reviewerColumns}
        data={rows}
        rowHeight={ROW_HEIGHT.small}
        getRowId={(row) => row.username}
      />
    </div>
  );
};

export default ReviewersSection;

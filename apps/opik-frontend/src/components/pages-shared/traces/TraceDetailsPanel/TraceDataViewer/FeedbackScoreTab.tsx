import React, { useMemo } from "react";
import sortBy from "lodash/sortBy";
import { TraceFeedbackScore } from "@/types/traces";
import FeedbackScoreRowDeleteCell from "./FeedbackScoreRowDeleteCell";
import FeedbackScoreValueCell from "./FeedbackScoreValueCell";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import DataTable from "@/components/shared/DataTable/DataTable";
import FeedbackScoreNameCell from "@/components/shared/DataTableCells/FeedbackScoreNameCell";
import FeedbackScoreReasonCell from "@/components/shared/DataTableCells/FeedbackScoreReasonCell";
import { FEEDBACK_SCORE_SOURCE_MAP } from "@/lib/feedback-scores";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import useLocalStorageState from "use-local-storage-state";
import FeedbackScoreTableNoData from "./FeedbackScoreTableNoData";
import { ExternalLink, InfoIcon } from "lucide-react";
import { buildDocsUrl } from "@/lib/utils";
import { Button } from "@/components/ui/button";

const SELECTED_COLUMNS_KEY = "trace-feedback-scores-tab-selected-columns";
const COLUMNS_ORDER_KEY = "trace-feedback-scores-tab-columns-order";

export const DEFAULT_COLUMNS: ColumnData<TraceFeedbackScore>[] = [
  {
    id: "source",
    label: "Source",
    type: COLUMN_TYPE.string,
    size: 100,
    accessorFn: (row) => FEEDBACK_SCORE_SOURCE_MAP[row.source],
  },
  {
    id: "name",
    label: "Key",
    type: COLUMN_TYPE.string,
    size: 100,
    cell: FeedbackScoreNameCell as never,
  },
  {
    id: "value",
    label: "Score",
    type: COLUMN_TYPE.string,
    cell: FeedbackScoreValueCell as never,
    size: 100,
  },
  {
    id: "reason",
    label: "Reason",
    type: COLUMN_TYPE.string,
    cell: FeedbackScoreReasonCell as never,
    size: 100,
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
    size: 100,
  },
];

const DEFAULT_SELECTED_COLUMNS = ["name", "value", "reason"];

type FeedbackScoreTabProps = {
  onDeleteFeedbackScore: (name: string) => void;
  onAddHumanReview: () => void;
  entityName: string;
  feedbackScores?: TraceFeedbackScore[];
};

const FeedbackScoreTab: React.FunctionComponent<FeedbackScoreTabProps> = ({
  onDeleteFeedbackScore,
  onAddHumanReview,
  entityName,
  feedbackScores = [],
}) => {
  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY,
    {
      defaultValue: DEFAULT_SELECTED_COLUMNS,
    },
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    COLUMNS_ORDER_KEY,
    {
      defaultValue: [],
    },
  );

  const columns = useMemo(() => {
    const retVal = convertColumnDataToColumn<
      TraceFeedbackScore,
      TraceFeedbackScore
    >(DEFAULT_COLUMNS, { selectedColumns, columnsOrder });

    retVal.push({
      id: "delete",
      enableHiding: false,
      cell: FeedbackScoreRowDeleteCell,
      meta: {
        custom: {
          onDelete: onDeleteFeedbackScore,
          entityName,
        },
      },
      size: 48,
      enableResizing: false,
    });

    return retVal;
  }, [onDeleteFeedbackScore, selectedColumns, columnsOrder, entityName]);

  const sortedFeedbackScores: TraceFeedbackScore[] = useMemo(
    () => sortBy(feedbackScores, "name"),
    [feedbackScores],
  );

  const scoreDocsLink = buildDocsUrl("/tracing/annotate_traces");

  return (
    <>
      <div className="mb-4 flex justify-end">
        <ColumnsButton
          columns={DEFAULT_COLUMNS}
          selectedColumns={selectedColumns}
          onSelectionChange={setSelectedColumns}
          order={columnsOrder}
          onOrderChange={setColumnsOrder}
        ></ColumnsButton>
      </div>

      <DataTable
        columns={columns}
        data={sortedFeedbackScores}
        noData={
          <FeedbackScoreTableNoData onAddHumanReview={onAddHumanReview} />
        }
      />

      {sortedFeedbackScores.length > 0 && (
        <div className="comet-body-xs mt-2 flex gap-1.5 py-2 text-light-slate">
          <div className="pt-[3px]">
            <InfoIcon className="size-3" />
          </div>
          <div className="leading-relaxed">
            Use the SDK or Online evaluation rules to
            <Button
              size="sm"
              variant="link"
              className="comet-body-xs inline-flex h-auto gap-0.5 px-1"
              asChild
            >
              <a href={scoreDocsLink} target="_blank" rel="noopener noreferrer">
                automatically score
                <ExternalLink className="size-3" />
              </a>
            </Button>
            your threads, or manually annotate your thread with
            <Button
              size="sm"
              variant="link"
              className="comet-body-xs inline-flex h-auto gap-0.5 px-1"
              onClick={onAddHumanReview}
            >
              human review
            </Button>
            .
          </div>
        </div>
      )}
    </>
  );
};

export default FeedbackScoreTab;

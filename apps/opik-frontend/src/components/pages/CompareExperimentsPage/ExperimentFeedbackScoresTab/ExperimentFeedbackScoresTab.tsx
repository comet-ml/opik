import React, { useMemo } from "react";
import useLocalStorageState from "use-local-storage-state";
import { ColumnPinningState } from "@tanstack/react-table";
import find from "lodash/find";
import uniq from "lodash/uniq";

import {
  AggregatedFeedbackScore,
  COLUMN_TYPE,
  ColumnData,
  SCORE_TYPE_EXPERIMENT,
  SCORE_TYPE_FEEDBACK,
  ScoreType,
} from "@/types/shared";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import TextCell from "@/components/shared/DataTableCells/TextCell";
import FeedbackScoreNameCell from "@/components/shared/DataTableCells/FeedbackScoreNameCell";
import CompareExperimentsHeader from "@/components/pages-shared/experiments/CompareExperimentsHeader/CompareExperimentsHeader";
import CompareExperimentsActionsPanel from "@/components/pages/CompareExperimentsPage/CompareExperimentsActionsPanel";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import Loader from "@/components/shared/Loader/Loader";
import { convertColumnDataToColumn } from "@/lib/table";
import { Experiment } from "@/types/datasets";

interface GetFeedbackScoreMapArguments {
  experiments: {
    id: string;
    feedback_scores?: AggregatedFeedbackScore[];
    experiment_scores?: AggregatedFeedbackScore[];
  }[];
}

export type FeedbackScoreData = {
  name: string;
} & Record<string, number>;

type FiledValue = string | number | undefined | null;

type FeedbackScoreMap = Record<string, Record<string, number>>;
type ScoreTypeMap = Record<string, ScoreType>;

export const buildScoreTypeMap = (
  experiments: {
    feedback_scores?: AggregatedFeedbackScore[];
    experiment_scores?: AggregatedFeedbackScore[];
  }[],
): ScoreTypeMap => {
  const scoreTypeMap: ScoreTypeMap = {};

  experiments.forEach((experiment) => {
    (experiment.feedback_scores ?? []).forEach((score) => {
      if (!scoreTypeMap[score.name]) {
        scoreTypeMap[score.name] = SCORE_TYPE_FEEDBACK;
      }
    });
    (experiment.experiment_scores ?? []).forEach((score) => {
      if (!scoreTypeMap[score.name]) {
        scoreTypeMap[score.name] = SCORE_TYPE_EXPERIMENT;
      }
    });
  });

  return scoreTypeMap;
};

export const getFeedbackScoreMap = ({
  experiments,
}: GetFeedbackScoreMapArguments): FeedbackScoreMap => {
  return experiments.reduce<FeedbackScoreMap>((acc, e) => {
    const scores = [
      ...(e.experiment_scores ?? []),
      ...(e.feedback_scores ?? []),
    ];

    acc[e.id] = scores.reduce<Record<string, number>>((a, f) => {
      a[f.name] = f.value;
      return a;
    }, {});

    return acc;
  }, {});
};

interface GetFeedbackScoresForExperimentsAsRowsArguments {
  feedbackScoresMap: FeedbackScoreMap;
  experimentsIds: string[];
  scoreTypeMap: ScoreTypeMap;
}

export const getFeedbackScoresForExperimentsAsRows = ({
  feedbackScoresMap,
  experimentsIds,
  scoreTypeMap,
}: GetFeedbackScoresForExperimentsAsRowsArguments) => {
  const keys = uniq(
    Object.values(feedbackScoresMap).reduce<string[]>(
      (acc, map) => acc.concat(Object.keys(map)),
      [],
    ),
  ).sort();

  return keys.map((key) => {
    const data = experimentsIds.reduce<Record<string, FiledValue>>(
      (acc, id: string) => {
        acc[id] = feedbackScoresMap[id]?.[key] ?? "-";
        return acc;
      },
      {},
    );

    const isFeedbackScore = scoreTypeMap[key] === SCORE_TYPE_FEEDBACK;
    const name = isFeedbackScore ? `${key} (avg)` : key;

    return {
      name,
      ...data,
    } as FeedbackScoreData;
  });
};

const COLUMNS_WIDTH_KEY = "compare-experiments-feedback-scores-columns-width";

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: ["name"],
  right: [],
};

export const DEFAULT_COLUMNS: ColumnData<FeedbackScoreData>[] = [
  {
    id: "name",
    label: "Feedback score",
    type: COLUMN_TYPE.numberDictionary,
    cell: FeedbackScoreNameCell as never,
    size: 248,
  },
];

export type ExperimentFeedbackScoresTabProps = {
  experimentsIds: string[];
  experiments: Experiment[];
  isPending: boolean;
};
const ExperimentFeedbackScoresTab: React.FunctionComponent<
  ExperimentFeedbackScoresTabProps
> = ({ experimentsIds, experiments, isPending }) => {
  const isCompare = experimentsIds.length > 1;

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const columns = useMemo(() => {
    const retVal = convertColumnDataToColumn<
      FeedbackScoreData,
      FeedbackScoreData
    >(DEFAULT_COLUMNS, {});

    experimentsIds.forEach((id: string) => {
      retVal.push({
        accessorKey: id,
        header: CompareExperimentsHeader as never,
        cell: TextCell as never,
        meta: {
          type: COLUMN_TYPE.number,
          custom: {
            experiment: find(experiments, (e) => e.id === id),
          },
        },
        size: 400,
        minSize: 120,
      });
    });

    return retVal;
  }, [experimentsIds, experiments]);

  const scoreTypeMap = useMemo(() => {
    return buildScoreTypeMap(experiments);
  }, [experiments]);

  const feedbackScoresMap = useMemo(() => {
    return getFeedbackScoreMap({
      experiments,
    });
  }, [experiments]);

  const rows = useMemo(() => {
    return getFeedbackScoresForExperimentsAsRows({
      feedbackScoresMap,
      experimentsIds,
      scoreTypeMap,
    });
  }, [feedbackScoresMap, experimentsIds, scoreTypeMap]);

  const noDataText = isCompare
    ? "These experiments have no feedback scores"
    : "This experiment has no feedback scores";

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  if (isPending) {
    return <Loader />;
  }

  return (
    <>
      <PageBodyStickyContainer
        className="-mt-4 flex items-center justify-end gap-8 pb-6 pt-4"
        direction="bidirectional"
        limitWidth
      >
        <div className="flex items-center gap-2">
          <CompareExperimentsActionsPanel />
        </div>
      </PageBodyStickyContainer>
      <DataTable
        columns={columns}
        data={rows}
        resizeConfig={resizeConfig}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={<DataTableNoData title={noDataText} />}
        TableWrapper={PageBodyStickyTableWrapper}
        stickyHeader
      />
      <PageBodyStickyContainer
        className="pb-6"
        direction="horizontal"
        limitWidth
      />
    </>
  );
};

export default ExperimentFeedbackScoresTab;

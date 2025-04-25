import uniq from "lodash/uniq";
import { AggregatedFeedbackScore, ROW_HEIGHT } from "@/types/shared";

interface GetFeedbackScoreMapArguments {
  experiments: {
    id: string;
    feedback_scores?: AggregatedFeedbackScore[];
  }[];
}

export type FeedbackScoreData = {
  name: string;
} & Record<string, number>;

type FiledValue = string | number | undefined | null;

type FeedbackScoreMap = Record<string, Record<string, number>>;

export const calculateLineHeight = (
  height: ROW_HEIGHT,
  lineCount: number = 1,
) => {
  const lineHeight = 32;
  const lineHeightMap: Record<ROW_HEIGHT, number> = {
    [ROW_HEIGHT.small]: 1,
    [ROW_HEIGHT.medium]: 4,
    [ROW_HEIGHT.large]: 12,
  };

  return {
    height: `${lineCount * lineHeightMap[height] * lineHeight}px`,
  };
};

export const getFeedbackScoreMap = ({
  experiments,
}: GetFeedbackScoreMapArguments): FeedbackScoreMap => {
  return experiments.reduce<FeedbackScoreMap>((acc, e) => {
    acc[e.id] = (e.feedback_scores || [])?.reduce<Record<string, number>>(
      (a, f) => {
        a[f.name] = f.value;
        return a;
      },
      {},
    );

    return acc;
  }, {});
};

interface GetFeedbackScoresForExperimentsAsRowsArguments {
  feedbackScoresMap: FeedbackScoreMap;
  experimentsIds: string[];
}

export const getFeedbackScoresForExperimentsAsRows = ({
  feedbackScoresMap,
  experimentsIds,
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

    return {
      name: key,
      ...data,
    } as FeedbackScoreData;
  });
};

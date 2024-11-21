// ALEX
import uniq from "lodash/uniq";
import { TraceFeedbackScore } from "@/types/traces";

interface GetFeedbackScoreMapArguments {
  experiments: {
    id: string;
    feedback_scores: TraceFeedbackScore[];
  }[];
}

// ALEX
// to see where to put this file

export type FeedbackScoreData = {
  name: string;
} & Record<string, number>;

type FiledValue = string | number | undefined | null;

type FeedbackScoreMap = Record<string, Record<string, number>>;

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

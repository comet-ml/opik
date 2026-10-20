import isString from "lodash/isString";
import findLast from "lodash/findLast";
import {
  getJSONPathRootKey,
  getValueByJSONPath,
  parseJSONPath,
} from "@/lib/utils";
import {
  AnnotationQueueAnnotatorView,
  AnnotatorViewField,
} from "@/types/annotation-queues";
import { TraceFeedbackScore } from "@/types/traces";

export const EMPTY_ANNOTATOR_VIEW: AnnotationQueueAnnotatorView = {};

export const ATTACHMENTS_PATH = "attachments";
export const FEEDBACK_SCORES_PATH = "feedback_scores";
export const MAX_FIELD_LABEL_LENGTH = 150;

const ROOT_LABELS: Record<string, string> = {
  input: "Input",
  output: "Output",
  metadata: "Metadata",
  [ATTACHMENTS_PATH]: "Attachments",
  [FEEDBACK_SCORES_PATH]: "Feedback scores",
};

export const DEFAULT_ANNOTATOR_FIELDS: AnnotatorViewField[] = [
  { label: "Attachments", path: ATTACHMENTS_PATH },
  { label: "Input", path: "input" },
  { label: "Output", path: "output" },
  { label: "Metadata", path: "metadata" },
];

export const getAnnotatorFields = (view?: AnnotationQueueAnnotatorView) =>
  view?.fields ?? DEFAULT_ANNOTATOR_FIELDS;

export type AnnotatorFieldKind = "attachments" | "feedback_scores" | "value";

export const getFieldKind = (path: string): AnnotatorFieldKind => {
  const root = getJSONPathRootKey(path);
  if (root === ATTACHMENTS_PATH) return "attachments";
  if (root === FEEDBACK_SCORES_PATH) return "feedback_scores";
  return "value";
};

export const getFieldScores = (
  path: string,
  scores: TraceFeedbackScore[] = [],
) => {
  const name = parseJSONPath(path)[1];
  if (name === undefined) return scores;
  const scoreName = "key" in name ? name.key : String(name.index);
  return scores.filter((score) => score.name === scoreName);
};

export const labelFromPath = (path: string) =>
  ROOT_LABELS[path] ??
  findLast(
    parseJSONPath(path),
    (part): part is { key: string } => "key" in part,
  )?.key ??
  path;

export type AnnotatorTraceSource = {
  input: object;
  output: object;
  metadata?: object;
};

export const buildFieldsTree = (
  source: AnnotatorTraceSource,
  feedbackScores: TraceFeedbackScore[] = [],
) => ({
  input: source.input,
  output: source.output,
  metadata: source.metadata ?? {},
  [ATTACHMENTS_PATH]: "All attachments",
  [FEEDBACK_SCORES_PATH]: Object.fromEntries(
    feedbackScores.map((score) => [score.name, score.value]),
  ),
});

export const resolveMessage = (data: unknown, path?: string) =>
  path ? getValueByJSONPath(data, path) : undefined;

export const messagePreviewText = (value: unknown) => {
  if (!isString(value)) return JSON.stringify(value)?.slice(0, 80) ?? "";
  return value
    .replace(/<\/?(p|div|br|li|h\d)\b[^>]*>/gi, " ")
    .replace(/<[^>]+>/g, "")
    .replace(/\s+/g, " ")
    .trim();
};

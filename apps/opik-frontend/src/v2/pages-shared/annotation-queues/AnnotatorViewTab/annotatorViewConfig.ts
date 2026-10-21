import isArray from "lodash/isArray";
import isObject from "lodash/isObject";
import isString from "lodash/isString";
import findLast from "lodash/findLast";
import {
  getJSONPathRootKey,
  getValueByJSONPath,
  parseJSONPath,
  quoteKey,
} from "@/lib/utils";
import {
  AnnotationQueueAnnotatorView,
  AnnotatorViewField,
} from "@/types/annotation-queues";
import { TraceFeedbackScore } from "@/types/traces";
import { prettifyMessage } from "@/lib/traces";

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
  const nameParts = parseJSONPath(path).slice(1);
  if (!nameParts.length) return scores;
  const scoreName = nameParts
    .map((part) => ("key" in part ? part.key : String(part.index)))
    .join(".");
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

const BLOCK_TAG_REGEX =
  /<\/?(?:p|div|br|li|ul|ol|h[1-6]|tr|table|blockquote|pre|hr)\b[^<>]*>/gi;
const INLINE_TAG_REGEX =
  /<\/?(?:span|b|i|u|s|em|strong|a|code|small|sub|sup|mark|font|img)\b[^<>]*>/gi;

export const extractMessageText = (value: unknown) => {
  if (!isArray(value)) return value;
  const texts = value
    .filter(
      (part): part is { type: "text"; text: string } =>
        isObject(part) &&
        (part as { type?: unknown }).type === "text" &&
        isString((part as { text?: unknown }).text),
    )
    .map((part) => part.text);
  return texts.length ? texts.join("\n\n") : value;
};

export const messagePreviewText = (value: unknown) => {
  const text = extractMessageText(value);
  if (!isString(text)) return JSON.stringify(text)?.slice(0, 80) ?? "";
  return text
    .replace(BLOCK_TAG_REGEX, " ")
    .replace(INLINE_TAG_REGEX, "")
    .replace(/\s+/g, " ")
    .trim();
};

export const getMessagePreview = (
  data: object | undefined,
  type: "input" | "output",
  path?: string,
) => {
  if (!data) return "";
  const value = resolveMessage(data, path);
  if (value !== undefined) return messagePreviewText(value);
  const { message } = prettifyMessage(data, { type });
  if (isString(message)) return message;
  return JSON.stringify(data).slice(0, 80);
};

const PATH_SYNTAX_CHARS = /[.[\]]/;

export const toAnnotatorPath = (tree: unknown, treePath: string) => {
  let rest = treePath;
  let node = tree;
  let path = "";
  while (rest) {
    const index = /^\[(\d+)\]/.exec(rest);
    if (index) {
      path += index[0];
      node = isArray(node) ? node[Number(index[1])] : undefined;
      rest = rest.slice(index[0].length);
      continue;
    }
    if (!isObject(node) || isArray(node)) return treePath;
    const remaining = rest.startsWith(".") && path ? rest.slice(1) : rest;
    const key = Object.keys(node)
      .filter(
        (candidate) =>
          remaining === candidate ||
          remaining.startsWith(`${candidate}.`) ||
          remaining.startsWith(`${candidate}[`),
      )
      .sort((a, b) => b.length - a.length)[0];
    if (key === undefined) return treePath;
    if (PATH_SYNTAX_CHARS.test(key)) path += quoteKey(key);
    else path += path ? `.${key}` : key;
    node = (node as Record<string, unknown>)[key];
    rest = remaining.slice(key.length);
  }
  return path;
};

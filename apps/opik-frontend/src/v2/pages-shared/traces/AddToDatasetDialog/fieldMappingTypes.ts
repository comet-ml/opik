import { EnrichmentOptions } from "./useAddToDatasetForm";

export type MappingRowKind = "fixed" | "custom" | "managed";

export type MappingRow = {
  id: string;
  name: string;
  kind: MappingRowKind;
  path?: string;
  option?: keyof EnrichmentOptions;
  touched?: boolean;
};

export type QuickAddChip = {
  option: keyof EnrichmentOptions;
  key: string;
  label: string;
  tracesOnly?: boolean;
};

export const MAX_FIELD_NAME_LENGTH = 150;

export const FIXED_MAPPING_FIELDS: Array<{ key: string; defaultPath: string }> =
  [
    { key: "input", defaultPath: "input" },
    { key: "expected_output", defaultPath: "output" },
  ];

export const QUICK_ADD_CHIPS: QuickAddChip[] = [
  {
    option: "includeFeedbackScores",
    key: "feedback_scores",
    label: "Feedback scores",
  },
  {
    option: "includeSpans",
    key: "spans",
    label: "Nested spans",
    tracesOnly: true,
  },
  { option: "includeTags", key: "tags", label: "Tags" },
  { option: "includeComments", key: "comments", label: "Comments" },
  { option: "includeUsage", key: "usage", label: "Usage metrics" },
  { option: "includeMetadata", key: "metadata", label: "Metadata" },
];

import { createEnumParam } from "use-query-params";

export const DetailsActionSection = {
  Annotate: "annotate",
  Annotations: "annotations",
  Comments: "comments",
} as const;
export type DetailsActionSectionValue =
  (typeof DetailsActionSection)[keyof typeof DetailsActionSection];

export const DetailsActionSectionParam =
  createEnumParam<DetailsActionSectionValue>([
    DetailsActionSection.Annotate,
    DetailsActionSection.Annotations,
    DetailsActionSection.Comments,
  ]);

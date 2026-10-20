import { useMemo, useState } from "react";
import uniqueId from "lodash/uniqueId";
import {
  AnnotationQueueAnnotatorView,
  AnnotatorViewField,
} from "@/types/annotation-queues";
import {
  MAX_FIELD_LABEL_LENGTH,
  getAnnotatorFields,
} from "./annotatorViewConfig";

export type AnnotatorFieldDraft = AnnotatorViewField & { id: string };

export type AnnotatorViewDraft = {
  input?: string;
  output?: string;
  fields: AnnotatorFieldDraft[];
};

export const toFieldDraft = (
  field: AnnotatorViewField,
): AnnotatorFieldDraft => ({
  ...field,
  id: uniqueId("annotator-field-"),
});

export const isFieldLabelInvalid = (field: AnnotatorViewField) =>
  !field.label.trim() || field.label.length > MAX_FIELD_LABEL_LENGTH;

type UseAnnotatorViewDraftParams = {
  view?: AnnotationQueueAnnotatorView;
  isThreadScope: boolean;
};

const useAnnotatorViewDraft = ({
  view,
  isThreadScope,
}: UseAnnotatorViewDraftParams) => {
  const [draft, setDraft] = useState<AnnotatorViewDraft>(() => ({
    input: view?.input,
    output: view?.output,
    fields: getAnnotatorFields(view).map(toFieldDraft),
  }));

  const annotatorView = useMemo<AnnotationQueueAnnotatorView>(
    () => ({
      input: draft.input,
      output: draft.output,
      ...(!isThreadScope && {
        fields: draft.fields.map(({ label, path, half_width }) => ({
          label: label.trim(),
          path,
          ...(half_width && { half_width }),
        })),
      }),
    }),
    [draft, isThreadScope],
  );

  const isInvalid =
    !isThreadScope &&
    draft.fields.some((field) => isFieldLabelInvalid(field) || !field.path);

  return { draft, setDraft, annotatorView, isInvalid };
};

export default useAnnotatorViewDraft;

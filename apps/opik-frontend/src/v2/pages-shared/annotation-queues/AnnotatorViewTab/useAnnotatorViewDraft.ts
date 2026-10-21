import { useMemo, useState } from "react";
import isEqual from "lodash/isEqual";
import omit from "lodash/omit";
import uniqueId from "lodash/uniqueId";
import {
  AnnotationQueueAnnotatorView,
  AnnotatorViewField,
} from "@/types/annotation-queues";
import {
  DEFAULT_ANNOTATOR_FIELDS,
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

const toSavedFields = (fields: AnnotatorFieldDraft[]) => {
  const saved = fields.map(({ label, path, half_width }) => ({
    label: label.trim(),
    path,
    ...(half_width && { half_width }),
  }));
  return isEqual(saved, DEFAULT_ANNOTATOR_FIELDS) ? {} : { fields: saved };
};

const withoutFieldIds = (draft: AnnotatorViewDraft) => ({
  ...draft,
  fields: draft.fields.map((field) => omit(field, "id")),
});

type UseAnnotatorViewDraftParams = {
  view?: AnnotationQueueAnnotatorView;
  isThreadScope: boolean;
};

const useAnnotatorViewDraft = ({
  view,
  isThreadScope,
}: UseAnnotatorViewDraftParams) => {
  const [initialDraft] = useState<AnnotatorViewDraft>(() => ({
    input: view?.input,
    output: view?.output,
    fields: getAnnotatorFields(view).map(toFieldDraft),
  }));
  const [draft, setDraft] = useState<AnnotatorViewDraft>(initialDraft);

  const annotatorView = useMemo<AnnotationQueueAnnotatorView>(
    () => ({
      input: draft.input,
      output: draft.output,
      ...(!isThreadScope && toSavedFields(draft.fields)),
    }),
    [draft, isThreadScope],
  );

  const isInvalid =
    !isThreadScope &&
    draft.fields.some((field) => isFieldLabelInvalid(field) || !field.path);

  const isDirty =
    draft !== initialDraft &&
    !isEqual(withoutFieldIds(draft), withoutFieldIds(initialDraft));

  return { draft, setDraft, annotatorView, isInvalid, isDirty };
};

export default useAnnotatorViewDraft;

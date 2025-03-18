import { foldService } from "@codemirror/language";

const FOLDABLE_FIELDS = ["inline_data:", "data:"];
const FOLDABLE_LIMIT = 1000;
export const yamlFoldService = foldService.of((state, lineStart) => {
  const line = state.doc.lineAt(lineStart);
  const text = line.text;

  if (text.length <= FOLDABLE_LIMIT) {
    return null;
  }

  const foldableField = FOLDABLE_FIELDS.find((field) => text.includes(field));

  if (foldableField) {
    const inlineDataIndex = text.indexOf(foldableField);
    const totalLength = foldableField.length + inlineDataIndex;

    return {
      from: line.from + totalLength,
      to: line.to,
    };
  }

  return null;
});

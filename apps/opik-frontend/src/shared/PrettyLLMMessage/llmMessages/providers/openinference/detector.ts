import {
  isOpenInferenceField,
  resolveOpenInferenceHint,
} from "@/lib/openinference";
import { FormatDetector, FormatHintResolver } from "../../types";

export const resolveOpenInferenceFormatHint: FormatHintResolver = (
  input,
  output,
) => {
  const hint = resolveOpenInferenceHint(undefined, input, output);
  return hint.detected
    ? {
        formatHint: "openinference",
        formatHintIsAuthoritative: hint.authoritative,
      }
    : undefined;
};

export const detectOpenInferenceFormat: FormatDetector = (
  data,
  prettifyConfig,
) => {
  const fieldType = prettifyConfig?.fieldType;
  if (!fieldType) return false;

  return isOpenInferenceField(
    data,
    fieldType,
    prettifyConfig?.formatHint === "openinference",
    prettifyConfig?.formatHintIsAuthoritative,
  );
};

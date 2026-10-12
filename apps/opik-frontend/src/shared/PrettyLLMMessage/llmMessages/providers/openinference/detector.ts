import { isOpenInferenceField } from "@/lib/openinference";
import { FormatDetector } from "../../types";

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

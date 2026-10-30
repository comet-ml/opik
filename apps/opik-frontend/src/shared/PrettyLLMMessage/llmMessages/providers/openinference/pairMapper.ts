import {
  hasLegacyOpenInferenceOutputAttributes,
  isOpenInferenceField,
} from "@/lib/openinference";
import { prettifyMessage } from "@/lib/traces";
import { FormatPairMapper } from "../../types";
import { mapOpenInferencePair } from "./mapper";

/** Recover historical output from input only when neither field belongs to another provider. */
export const mapOpenInferenceMessagePair: FormatPairMapper = (
  input,
  output,
  config,
) => {
  const detections = [input.detection, output.detection];
  if (
    detections.some(
      (detection) =>
        detection.supported &&
        detection.format !== undefined &&
        detection.format !== "openinference",
    ) ||
    !detections.some((detection) => detection.format === "openinference")
  ) {
    return null;
  }

  let currentOutput = output.raw;
  // Current generic answers precede stale output attributes in historical input.
  // Old {value, mime_type} envelopes remain fallbacks.
  if (
    config.formatHintIsAuthoritative !== false &&
    hasLegacyOpenInferenceOutputAttributes(input.raw) &&
    currentOutput != null &&
    (typeof currentOutput === "object" ||
      typeof currentOutput === "string" ||
      typeof currentOutput === "number" ||
      typeof currentOutput === "boolean") &&
    !(typeof currentOutput === "object" && "value" in currentOutput) &&
    !isOpenInferenceField(currentOutput, "output", true, false)
  ) {
    const pretty = prettifyMessage(currentOutput, { type: "output" });
    if (
      pretty.prettified &&
      typeof pretty.message === "string" &&
      pretty.message.trim()
    ) {
      currentOutput = {
        messages: [{ role: "assistant", content: pretty.message }],
      };
    }
  }
  return mapOpenInferencePair(
    input.raw,
    currentOutput,
    input.detection.supported,
    output.detection.supported,
  );
};

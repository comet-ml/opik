import { LLMMessageFormatImplementation } from "../../types";
import { detectOpenInferenceFormat } from "./detector";
import {
  combineOpenInferenceMessages,
  mapOpenInferenceMessages,
} from "./mapper";

export const openinferenceFormat: LLMMessageFormatImplementation = {
  name: "openinference",
  detector: detectOpenInferenceFormat,
  mapper: mapOpenInferenceMessages,
  combiner: combineOpenInferenceMessages,
};

export {
  combineOpenInferenceMessages,
  detectOpenInferenceFormat,
  mapOpenInferenceMessages,
};

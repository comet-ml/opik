import { LLMMessageFormatImplementation } from "../../types";
import { detectPlaygroundFormat } from "./detector";
import { mapPlaygroundMessages } from "./mapper";

export const playgroundFormat: LLMMessageFormatImplementation = {
  name: "playground",
  detector: detectPlaygroundFormat,
  mapper: mapPlaygroundMessages,
};

export { detectPlaygroundFormat, mapPlaygroundMessages };

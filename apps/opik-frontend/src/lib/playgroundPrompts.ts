import {
  PLAYGROUND_MESSAGE_TYPE,
  PlaygroundMessageType,
} from "@/types/playgroundPrompts";
import { generateRandomString } from "@/lib/utils";

export const generateDefaultPlaygroundPromptMessage = (
  message: Partial<PlaygroundMessageType> = {},
): PlaygroundMessageType => {
  return {
    text: "",
    type: PLAYGROUND_MESSAGE_TYPE.system,

    ...message,
    id: generateRandomString(),
  };
};

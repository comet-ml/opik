// ALEX CHANGE THE NAMES
export enum PLAYGROUND_PROVIDERS_TYPES {
  "OpenAI" = "OpenAI",
}

export enum PLAYGROUND_MODEL_TYPE {
  "gpt-4o" = "gpt-4o",
  "gpt-4o-mini" = "gpt-4o-mini",
  "gpt-4o-2024-11-20" = "gpt-4o-2024-11-20",
  "gpt-4o-2024-08-06" = "gpt-4o-2024-08-06",
  "gpt-4o-2024-05-13" = "gpt-4o-2024-05-13",
  "gpt-4-turbo" = "gpt-4-turbo",
  "gpt-4" = "gpt-4",
  "gpt-3.5-turbo" = "gpt-3.5-turbo",
  "chatgpt-4o-latest" = "chatgpt-4o-latest",
  "gpt-4o-mini-2024-07-18" = "gpt-4o-mini-2024-07-18",
}

export enum PLAYGROUND_MESSAGE_TYPE {
  system = "system",
  assistant = "assistant",
  user = "user",
}

export interface PlaygroundMessageType {
  text: string;
  id: string;
  type: PLAYGROUND_MESSAGE_TYPE;
}

export interface PlaygroundPromptType {
  name: string;
  id: string;
  messages: PlaygroundMessageType[];
  model: PLAYGROUND_MODEL_TYPE | "";
}

export interface PlaygroundOutputType {
  id: string;
  promptId: string;
  text: string;
}

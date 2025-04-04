export type JsonPrimitive = string | number | boolean | null;
export type JsonValue = JsonPrimitive | JsonArray | JsonObject;
export type JsonArray = JsonValue[];
export type JsonObject = { [key: string]: JsonValue };

export interface ContentContainer {
  content?: unknown;
}

export interface MessageContainer {
  messages?: unknown[] | unknown;
}

export interface ValueContainer {
  value?: unknown;
}

export interface KwargsContainer {
  kwargs?: unknown;
}

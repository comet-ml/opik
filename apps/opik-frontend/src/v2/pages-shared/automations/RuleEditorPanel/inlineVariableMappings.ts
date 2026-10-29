import isString from "lodash/isString";

import { LLMMessage, MessageContent } from "@/types/llm";

const ENTITY_PATH_REGEX = /^(input|output|metadata)(\.|\[|$)/;
const NESTED_ENTITY_PATH_REGEX = /^(input|output|metadata)(\.|\[)/;

const TAG_REGEX = /\{\{\s*([^{}]*?)\s*\}\}/g;

const replaceTags = (text: string, renames: Map<string, string>) =>
  text.replace(TAG_REGEX, (tag, name: string) => {
    const newName = renames.get(name);
    return newName === undefined ? tag : `{{${newName}}}`;
  });

export const isInlinedMapping = (path: string) => ENTITY_PATH_REGEX.test(path);

const mapContentStrings = (
  content: MessageContent,
  fn: (value: string) => string,
): MessageContent => {
  if (isString(content)) return fn(content);

  return content.map((part) => {
    switch (part.type) {
      case "text":
        return { ...part, text: fn(part.text) };
      case "image_url":
        return { ...part, image_url: { url: fn(part.image_url.url) } };
      case "video_url":
        return { ...part, video_url: { url: fn(part.video_url.url) } };
      default:
        return { ...part, audio_url: { url: fn(part.audio_url.url) } };
    }
  });
};

const renameTags = (messages: LLMMessage[], renames: [string, string][]) => {
  const renameMap = new Map(renames);
  return messages.map((message) => ({
    ...message,
    content: mapContentStrings(message.content, (value) =>
      replaceTags(value, renameMap),
    ),
  }));
};

export const inlineVariableMappings = (
  messages: LLMMessage[],
  variables: Record<string, string>,
): LLMMessage[] =>
  renameTags(
    messages,
    Object.entries(variables).filter(([, path]) => isInlinedMapping(path)),
  );

export const nameInlineVariables = (
  messages: LLMMessage[],
  variables: Record<string, string>,
  savedVariables: Record<string, string> = {},
): { messages: LLMMessage[]; variables: Record<string, string> } => {
  const isInlinePath = (name: string) =>
    variables[name] === name && NESTED_ENTITY_PATH_REGEX.test(name);
  const usedNames = new Set(
    Object.keys(variables).filter((name) => !isInlinePath(name)),
  );
  let counter = 0;
  const nextFreeName = () => {
    let name;
    do {
      counter += 1;
      name = `var_${counter}`;
    } while (usedNames.has(name));
    return name;
  };

  const namedVariables: Record<string, string> = {};
  const renames: [string, string][] = [];

  Object.entries(variables).forEach(([name, path]) => {
    if (!isInlinePath(name)) {
      namedVariables[name] = path;
      return;
    }

    const savedName = Object.keys(savedVariables).find(
      (saved) =>
        savedVariables[saved] === path &&
        saved !== path &&
        !usedNames.has(saved),
    );
    const newName = savedName ?? nextFreeName();
    usedNames.add(newName);
    namedVariables[newName] = path;
    renames.push([name, newName]);
  });

  return {
    messages: renameTags(messages, renames),
    variables: namedVariables,
  };
};

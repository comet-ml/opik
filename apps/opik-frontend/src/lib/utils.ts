import { type ClassValue, clsx } from "clsx";
import isObject from "lodash/isObject";
import isArray from "lodash/isArray";
import last from "lodash/last";
import get from "lodash/get";
import round from "lodash/round";
import isUndefined from "lodash/isUndefined";
import times from "lodash/times";
import sample from "lodash/sample";
import mapKeys from "lodash/mapKeys";
import snakeCase from "lodash/snakeCase";
import { twMerge } from "tailwind-merge";
import { DEFAULT_WORKSPACE_NAME } from "@/constants/user";
import { JsonNode } from "@/types/shared";

const BASE_DOCUMENTATION_URL = "https://www.comet.com/docs/opik";

export const buildDocsUrl = (path: string = "", hash: string = "") => {
  return `${BASE_DOCUMENTATION_URL}${path}?from=llm${hash}`;
};

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export const isValidJsonObject = (string: string) => {
  let json = null;
  try {
    json = JSON.parse(string);
  } catch (e) {
    return false;
  }

  return json && isObject(json);
};

export const safelyParseJSON = (string: string) => {
  try {
    return JSON.parse(string);
  } catch (e) {
    console.error(e);
    return {};
  }
};

export const getJSONPaths = (
  node: JsonNode,
  previousPath: string = "",
  results: string[] = [],
) => {
  if (isObject(node) || isArray(node)) {
    for (const key in node) {
      const value = get(node, key);
      const path = previousPath
        ? isArray(node)
          ? `${previousPath}[${key}]`
          : `${previousPath}.${key}`
        : key;

      if (isArray(value)) {
        getJSONPaths(value, path, results);
      } else if (isObject(value)) {
        getJSONPaths(value, path, results);
      } else {
        results.push(path);
      }
    }
  }

  return results;
};

export const getTextWidth = (
  text: string[],
  properties: {
    font?: string;
  } = {},
) => {
  const canvas = document.createElement("canvas");
  const context = canvas.getContext("2d") as CanvasRenderingContext2D;

  if (properties.font) {
    context.font = properties.font;
  }

  return text.map((v) => context.measureText(v).width);
};

export const toString = (value?: string | number | boolean | null) =>
  isUndefined(value) ? "" : String(value);

export const maskAPIKey = (apiKey: string = "") =>
  `${apiKey.substring(0, 6)}*****************`;

export const generateRandomString = (length: number = 6): string => {
  const characters =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  return times(length, () => sample(characters)).join("");
};

export const getAlphabetLetter = (i: number) => {
  const characters =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

  return characters.charAt(i % characters.length);
};

export const snakeCaseObj = <T extends object>(obj: T) => {
  return mapKeys(obj, (_, key) => snakeCase(key));
};

export const calculateWorkspaceName = (
  workspaceName: string,
  defaultName = "Personal",
) => (workspaceName === DEFAULT_WORKSPACE_NAME ? defaultName : workspaceName);

export const extractIdFromLocation = (location: string) =>
  last(location?.split("/"));

export const formatNumericData = (value: number, precision = 3) =>
  String(round(value, precision));

export const buildApiKeyConfig = (apiKey: string, masked = false) =>
  `os.environ["OPIK_API_KEY"] = "${masked ? maskAPIKey(apiKey) : apiKey}"`;
export const buildWorkspaceNameConfig = (workspaceName: string) =>
  `os.environ["OPIK_WORKSPACE"] = "${workspaceName}"`;

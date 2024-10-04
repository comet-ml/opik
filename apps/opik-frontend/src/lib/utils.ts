import { type ClassValue, clsx } from "clsx";
import round from "lodash/round";
import isObject from "lodash/isObject";
import isUndefined from "lodash/isUndefined";
import { twMerge } from "tailwind-merge";

const BASE_COMET_URL = import.meta.env.VITE_BASE_COMET_URL;

export const buildDocsUrl = (path: string, hash: string = "") => {
  const url = BASE_COMET_URL
    ? `${BASE_COMET_URL}/docs/opik`
    : "https://comet.com/docs/opik";

  return `${url}${path}?from=llm${hash}`;
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

export const calcDuration = (start: string, end: string) => {
  return new Date(end).getTime() - new Date(start).getTime();
};

export const millisecondsToSeconds = (milliseconds: number) => {
  // rounds with precision, one character after the point
  return round(milliseconds / 1000, 1);
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

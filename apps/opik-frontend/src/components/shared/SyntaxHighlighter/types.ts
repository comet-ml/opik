import { MODE_TYPE } from "./constants";

export type PrettifyConfig = {
  fieldType: "input" | "output";
};

export type CodeOutput = {
  message: string;
  mode: MODE_TYPE;
  prettified: boolean;
  canBePrettified: boolean;
};

import React from "react";
import { COLUMN_TYPE } from "@/types/shared";
import { SORT_DIRECTION } from "@/types/sorting";

export interface Group {
  id: string;
  field: string;
  direction: SORT_DIRECTION;
  type: COLUMN_TYPE | "";
  key?: string;
}

export type GroupRowConfig = {
  keyComponent: React.FC<unknown> & {
    placeholder: string;
    value: string;
    onValueChange: (value: string) => void;
  };
  keyComponentProps: unknown;
};

export type Groups = Group[];

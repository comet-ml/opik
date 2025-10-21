import React from "react";
import { COLUMN_TYPE } from "@/types/shared";
import { SORT_DIRECTION } from "@/types/sorting";
import { Filters } from "@/types/filters";

export interface Group {
  id: string;
  field: string;
  direction: SORT_DIRECTION;
  type: COLUMN_TYPE | "";
  key?: string;
  error?: string;
}

export type GroupRowConfig = {
  keyComponent: React.FC<unknown> & {
    placeholder: string;
    value: string;
    onValueChange: (value: string) => void;
  };
  keyComponentProps: unknown;
  validateGroup?: (group: Group) => string | undefined;
  sortingMessage?: string;
};

export type Groups = Group[];

export type FlattenGroup = {
  id: string;
  rowGroupData: Record<string, unknown>;
  filters: Filters;
  metadataPath: string[];
  level: number;
};

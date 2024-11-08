import { ColumnSort } from "@tanstack/react-table";

export enum SORT_DIRECTION {
  ASC = "ASC",
  DESC = "DESC",
}

export type SortingField = {
  field: string;
  direction: SORT_DIRECTION;
};

export type Sorting = ColumnSort[];

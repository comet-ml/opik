import isNumber from "lodash/isNumber";
import isUndefined from "lodash/isUndefined";

import { Project } from "@/types/projects";
import { WorkspaceCost, WorkspaceMetric } from "@/types/workspaces";

export const RE_FETCH_INTERVAL = 30000;

export const ALL_PROJECTS_PROJECT = {
  id: "all",
  name: "All projects",
} as Project;

export type DataRecord = {
  date: string;
  map: Record<string, number | null>;
};

export type ChartData = {
  data: DataRecord[];
  values: number[];
  projects: Project[];
};

export const getChartData = (
  rowData: WorkspaceMetric[] | WorkspaceCost[] | undefined,
  projects: Project[],
  noValue?: number,
) => {
  const retVal: ChartData = {
    data: [],
    values: [],
    projects: projects.length === 0 ? [ALL_PROJECTS_PROJECT] : projects,
  };

  const datesMap: Record<string, DataRecord> = {};

  (rowData || []).forEach((dataObject) => {
    dataObject.data.forEach((d) => {
      if (!datesMap[d.time]) {
        datesMap[d.time] = { date: d.time, map: {} };
      }
      datesMap[d.time].map[dataObject.project_id || ALL_PROJECTS_PROJECT.id] =
        isNumber(d.value) ? d.value : isUndefined(noValue) ? d.value : noValue;
      retVal.values.push(d.value);
    });
  });
  retVal.data = Object.values(datesMap).sort(
    (a, b) => new Date(a.date).getTime() - new Date(b.date).getTime(),
  );

  return retVal;
};

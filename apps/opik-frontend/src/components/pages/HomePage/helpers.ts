import { Project } from "@/types/projects";
import { WorkspaceCost, WorkspaceMetric } from "@/types/workspaces";

export const RE_FETCH_INTERVAL = 30000;

export const ALL_PROJECTS_PROJECT = {
  id: "all",
  name: "All projects",
} as Project;

export type DataRecord = {
  date: string;
  map: Record<string, number>;
};

export type ChartData = {
  data: DataRecord[];
  values: number[];
  projects: Project[];
};

export const getChartData = (
  rowData: WorkspaceMetric[] | WorkspaceCost[] | undefined,
  projects: Project[],
) => {
  const data: ChartData = {
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
        d.value;
      data.values.push(d.value);
    });
  });
  data.data = Object.values(datesMap).sort(
    (a, b) => new Date(a.date).getTime() - new Date(b.date).getTime(),
  );

  return data;
};

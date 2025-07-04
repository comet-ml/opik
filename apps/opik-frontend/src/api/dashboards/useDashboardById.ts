import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DASHBOARDS_REST_ENDPOINT, DASHBOARDS_KEY, QueryConfig } from "@/api/api";

// Backend API response types
interface BackendDashboardPanel {
  id: string;
  name: string;
  type: 'PYTHON' | 'CHART' | 'TEXT' | 'METRIC' | 'HTML';
  configuration: any;
  layout: {
    x: number;
    y: number;
    w: number;
    h: number;
  };
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}

interface BackendDashboardSection {
  id: string;
  title: string;
  position_order: number;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
  panels: BackendDashboardPanel[];
}

interface BackendDashboardWithSections {
  id: string;
  name: string;
  description?: string;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
  sections: BackendDashboardSection[];
}

// Frontend types (with 'i' property in layout)
export interface DashboardWithSections {
  id: string;
  name: string;
  description?: string;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
  sections: DashboardSection[];
}

export interface DashboardSection {
  id: string;
  title: string;
  position_order: number;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
  panels: DashboardPanel[];
}

export interface DashboardPanel {
  id: string;
  name: string;
  type: 'PYTHON' | 'CHART' | 'TEXT' | 'METRIC' | 'HTML';
  configuration: any;
  layout: {
    i: string;
    x: number;
    y: number;
    w: number;
    h: number;
  };
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}

type UseDashboardByIdParams = {
  dashboardId: string;
};

const getDashboardById = async (
  { signal }: QueryFunctionContext,
  { dashboardId }: UseDashboardByIdParams,
) => {
  const { data } = await api.get<BackendDashboardWithSections>(
    `${DASHBOARDS_REST_ENDPOINT}${dashboardId}`,
    { signal }
  );

  // Transform the API response to match the expected frontend format
  const transformedData: DashboardWithSections = {
    ...data,
    sections: data.sections.map(section => ({
      ...section,
      panels: section.panels.map(panel => ({
        ...panel,
        layout: {
          i: panel.id, // Use panel ID as the layout item identifier
          x: panel.layout.x,
          y: panel.layout.y,
          w: panel.layout.w,
          h: panel.layout.h,
        }
      }))
    }))
  };

  return transformedData;
};

export default function useDashboardById(
  params: UseDashboardByIdParams,
  options?: QueryConfig<DashboardWithSections>,
) {
  return useQuery({
    queryKey: [DASHBOARDS_KEY, params],
    queryFn: (context) => getDashboardById(context, params),
    ...options,
  });
} 

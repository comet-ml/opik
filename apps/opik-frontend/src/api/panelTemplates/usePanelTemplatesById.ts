import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PANEL_TEMPLATES_REST_ENDPOINT, PANEL_TEMPLATES_KEY, QueryConfig } from "@/api/api";

export interface ReusablePanelTemplate {
  id: string;
  name: string;
  description?: string;
  type: 'PYTHON' | 'CHART' | 'TEXT' | 'METRIC' | 'HTML';
  configuration: any;
  default_layout: {
    x: number;
    y: number;
    w: number;
    h: number;
  };
  workspace_id: string;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}

type UsePanelTemplateByIdParams = {
  templateId: string;
};

const getPanelTemplateById = async (
  { signal }: QueryFunctionContext,
  { templateId }: UsePanelTemplateByIdParams,
) => {
  const { data } = await api.get<ReusablePanelTemplate>(
    `${PANEL_TEMPLATES_REST_ENDPOINT}${templateId}`,
    { signal }
  );

  return data;
};

export default function usePanelTemplateById(
  params: UsePanelTemplateByIdParams,
  options?: QueryConfig<ReusablePanelTemplate>,
) {
  return useQuery({
    queryKey: [PANEL_TEMPLATES_KEY, params],
    queryFn: (context) => getPanelTemplateById(context, params),
    ...options,
  });
} 
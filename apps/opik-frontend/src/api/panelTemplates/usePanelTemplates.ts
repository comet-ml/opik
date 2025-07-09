import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PANEL_TEMPLATES_REST_ENDPOINT, PANEL_TEMPLATES_KEY, QueryConfig } from "@/api/api";
import { ReusablePanelTemplate } from "./usePanelTemplatesById";

type UsePanelTemplatesParams = {
  type?: 'PYTHON' | 'CHART' | 'TEXT' | 'METRIC' | 'HTML';
};

const getPanelTemplates = async (
  { signal }: QueryFunctionContext,
  { type }: UsePanelTemplatesParams,
) => {
  const params = new URLSearchParams();
  if (type) {
    params.append('type', type);
  }

  const { data } = await api.get<ReusablePanelTemplate[]>(
    `${PANEL_TEMPLATES_REST_ENDPOINT}${params.toString() ? `?${params.toString()}` : ''}`,
    { signal }
  );

  return data;
};

export default function usePanelTemplates(
  params: UsePanelTemplatesParams = {},
  options?: QueryConfig<ReusablePanelTemplate[]>,
) {
  return useQuery({
    queryKey: [PANEL_TEMPLATES_KEY, params],
    queryFn: (context) => getPanelTemplates(context, params),
    ...options,
  });
} 
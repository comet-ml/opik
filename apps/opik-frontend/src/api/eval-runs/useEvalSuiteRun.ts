import { useQuery } from "@tanstack/react-query";
import { RegressionItem } from "@/types/agent-intake";

const INTAKE_BASE_URL = "http://localhost:5008";

export type EvalSuiteRunData = {
  run_id: string;
  suite_id?: string;
  suite_name?: string;
  items_tested: number;
  items_passed: number;
  items: RegressionItem[];
  created_at?: string;
};

const getEvalSuiteRun = async (runId: string): Promise<EvalSuiteRunData> => {
  const response = await fetch(`${INTAKE_BASE_URL}/eval-runs/${runId}`);
  if (!response.ok) {
    throw new Error(`Failed to fetch eval run: ${response.status}`);
  }
  return response.json();
};

type UseEvalSuiteRunParams = {
  runId: string;
};

export default function useEvalSuiteRun({ runId }: UseEvalSuiteRunParams) {
  return useQuery({
    queryKey: ["eval-suite-run", runId],
    queryFn: () => getEvalSuiteRun(runId),
    enabled: !!runId,
  });
}

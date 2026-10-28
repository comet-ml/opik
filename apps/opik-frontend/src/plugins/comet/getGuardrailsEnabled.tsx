import api from "./api";

const GUARDRAILS_FEATURE = "guardrails";

// The same platform check opik-backend runs before serving any guardrails endpoint: whether the organization of
// this workspace has guardrails enabled.
const getGuardrailsEnabled = async (
  workspaceName: string,
  signal?: AbortSignal,
): Promise<boolean> => {
  const { data } = await api.post<{ features: Record<string, boolean> }>(
    "/opik/workspace-features-session",
    { workspaceName, features: [GUARDRAILS_FEATURE] },
    { signal },
  );
  return Boolean(data.features[GUARDRAILS_FEATURE]);
};

export default getGuardrailsEnabled;

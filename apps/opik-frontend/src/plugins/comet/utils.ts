const BASE_URL = import.meta.env.VITE_BASE_COMET_URL || "/";
const FROM_PARAM = "?from=llm";

export const buildUrl = (
  path: string,
  workspaceName?: string,
  search: string = "",
) => {
  const workspaceNameParameter = workspaceName
    ? `&from_workspace_name=${workspaceName}`
    : "";

  return `${BASE_URL}${path}${FROM_PARAM}${workspaceNameParameter}${search}`;
};

import { isAiSpendRoute } from "@/lib/aiSpend";
import { Workspace, WORKSPACE_KIND } from "../types";

const AI_SPEND_NAME_PREFIX = "__cc_";
const AI_SPEND_NAME_SUFFIX = "__";

// Backend (OPIK-6819) will expose `kind`; until then derive it from the
// reserved `__cc_{orgId}__` workspace name so the FE works against a real
// provisioned workspace and switches transparently once `kind` arrives.
export const getWorkspaceKind = (
  workspace?: Workspace | null,
): WORKSPACE_KIND => {
  if (!workspace) return WORKSPACE_KIND.STANDARD;
  if (workspace.kind) return workspace.kind;

  const name = workspace.workspaceName ?? "";

  return name.startsWith(AI_SPEND_NAME_PREFIX) &&
    name.endsWith(AI_SPEND_NAME_SUFFIX)
    ? WORKSPACE_KIND.AI_SPEND
    : WORKSPACE_KIND.STANDARD;
};

export const isAiSpendWorkspace = (workspace?: Workspace | null): boolean =>
  getWorkspaceKind(workspace) === WORKSPACE_KIND.AI_SPEND;

// The spend workspace is a hidden data container: it may only be browsed inside
// the AI Spend area. Anywhere else it should be treated as inaccessible.
export const isHiddenSpendWorkspace = (
  workspace: Workspace | null | undefined,
  pathname: string,
): boolean => isAiSpendWorkspace(workspace) && !isAiSpendRoute(pathname);

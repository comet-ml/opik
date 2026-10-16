import { isAiSpendRoute } from "@/lib/aiSpend";
import { Workspace } from "../types";

const AI_SPEND_WORKSPACE_PATTERN = /^__(?:ai_spend|cc)_.+__$/;

export const isAiSpendWorkspace = (workspace?: Workspace | null): boolean =>
  AI_SPEND_WORKSPACE_PATTERN.test(workspace?.workspaceName ?? "");

// Lands on a spend workspace only when the user has no regular one; undefined sends them to login.
export const pickLandingWorkspace = (
  workspaces: Workspace[],
): Workspace | undefined => {
  const regular = workspaces.filter((w) => !isAiSpendWorkspace(w));
  return regular.find((w) => w.default) ?? regular[0] ?? workspaces[0];
};

export const isHiddenSpendWorkspace = (
  workspace: Workspace | null | undefined,
  pathname: string,
): boolean => isAiSpendWorkspace(workspace) && !isAiSpendRoute(pathname);

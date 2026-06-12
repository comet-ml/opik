import { isAiSpendRoute } from "@/lib/aiSpend";
import { Workspace } from "../types";

const AI_SPEND_WORKSPACE_PATTERN = /^__(?:ai_spend|cc)_.+__$/;

export const isAiSpendWorkspace = (workspace?: Workspace | null): boolean =>
  AI_SPEND_WORKSPACE_PATTERN.test(workspace?.workspaceName ?? "");

export const isHiddenSpendWorkspace = (
  workspace: Workspace | null | undefined,
  pathname: string,
): boolean => isAiSpendWorkspace(workspace) && !isAiSpendRoute(pathname);

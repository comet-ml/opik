import { APIWorkspaceMember } from "@/plugins/comet/useWorkspaceMembers";

export interface User {
  apiKeys: string[];
  defaultWorkspace: string;
  email: string;
  gitHub: boolean;
  loggedIn: boolean;
  orgReachedTraceLimit?: boolean;
  profileImages: ProfileImages;
  sagemakerRestrictions?: boolean;
  suspended: boolean;
  userName: string;
}

export interface GetTeams {
  teams: Team[];
}

export interface Team {
  academic: boolean;
  admin: boolean;
  default_team: boolean;
  organizationId: string;
  payment_plan: string;
  teamId: string;
  teamName: string;
}

export interface ProfileImages {
  small: string;
  large: string;
}

export const ORGANIZATION_PLAN_ENTERPRISE = "enterprise_organization";

export enum ORGANIZATION_ROLE_TYPE {
  admin = "ADMIN",
  member = "MEMBER",
  opik = "LLM_ONLY",
  viewOnly = "VIEW_ONLY_MEMBER",
  emAndMPMOnly = "EM_AND_MPM_ONLY",
}

export enum WORKSPACE_ROLE_TYPE {
  owner = "Workspace owner",
  member = "Workspace member",
}

export interface Organization {
  id: string;
  name: string;
  paymentPlan: string;
  academic: boolean;
  role: ORGANIZATION_ROLE_TYPE;
  onlyAdminsInviteByEmail: boolean;
}

export enum ManagementPermissionsNames {
  MANAGEMENT = "management",
  PROJECT_VISIBILITY = "project_visibility",
  INVITE_USERS = "invite_users_to_workspace",
}

export interface UserPermission {
  permissionName: ManagementPermissionsNames;
  permissionValue: "true" | "false";
}

export interface Workspace {
  createdAt: number;
  workspaceId: string;
  workspaceName: string;
  workspaceOwner: string;
  workspaceCreator: string;
  organizationId: string;
  collaborationFeaturesDisabled: boolean;
  default: boolean;
}

export interface OrganizationMember {
  userName: string;
  email: string;
  role: ORGANIZATION_ROLE_TYPE;
}

export interface WorkspaceMember extends APIWorkspaceMember {
  id: string;
  role: string;
  isAdmin: boolean;
  permissions: UserPermission[];
}

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
  workspaceRolesEnabled: boolean;
}

export enum ManagementPermissionsNames {
  MANAGEMENT = "management",
  PROJECT_VISIBILITY = "project_visibility",
  INVITE_USERS = "invite_users_to_workspace",
  EXPERIMENT_VIEW = "experiment_view",
  DASHBOARD_VIEW = "dashboard_view",
  DATASET_VIEW = "dataset_view",
  PROJECT_DELETE = "project_delete",
  ANNOTATION_QUEUE_CREATE = "annotation_queue_create",
  ANNOTATION_QUEUE_DELETE = "annotation_queue_delete",
  TRACE_DELETE = "trace_delete",
  PROMPT_DELETE = "prompt_delete",
  DATASET_DELETE = "dataset_delete",
  OPTIMIZATION_RUN_DELETE = "optimization_run_delete",
  USER_ROLE_UPDATE = "user_role_update",
  WORKSPACE_SETTINGS_CONFIGURE = "workspace_settings_configure",
  AI_PROVIDER_UPDATE = "ai_provider_update",
  PROJECT_CREATE = "project_create",
  COMMENT_WRITE = "comment_write",
  ALERT_UPDATE = "alert_update",
  TRACE_SPAN_THREAD_ANNOTATE = "trace_span_thread_annotate",
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
  roleId?: string;
  isAdmin: boolean;
  permissions: UserPermission[];
  permissionMismatch?: {
    message: string;
  };
}

export enum WorkspaceRoleType {
  DEFAULT = "DEFAULT",
  CUSTOM = "CUSTOM",
}

export interface WorkspaceRole {
  roleId: string;
  roleName: string;
  description: string;
  roleType: WorkspaceRoleType;
  permissions: string[];
  organizationId?: string;
  inheritedRoleId?: string | null;
  createdBy?: string | null;
  createdAt?: string | null;
  lastUpdatedBy?: string | null;
  lastUpdatedAt?: string | null;
  usersCount?: number;
  workspacesCount?: number;
}

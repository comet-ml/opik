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
  PROJECT_CREATE = "project_create",
  PROJECT_DELETE = "project_delete",
  INVITE_USERS = "invite_users_to_workspace",
  EXPERIMENT_VIEW = "experiment_view",
  EXPERIMENT_CREATE = "experiment_create",
  DASHBOARD_VIEW = "dashboard_view",
  DASHBOARD_CREATE = "dashboard_create",
  DASHBOARD_EDIT = "dashboard_edit",
  DASHBOARD_DELETE = "dashboard_delete",
  DATASET_VIEW = "dataset_view",
  DATASET_CREATE = "dataset_create",
  DATASET_EDIT = "dataset_edit",
  DATASET_DELETE = "dataset_delete",
  ANNOTATION_QUEUE_CREATE = "annotation_queue_create",
  ANNOTATION_QUEUE_EDIT = "annotation_queue_edit",
  ANNOTATION_QUEUE_DELETE = "annotation_queue_delete",
  TRACE_DELETE = "trace_delete",
  TRACE_SPAN_THREAD_ANNOTATE = "trace_span_thread_annotate",
  TRACE_SPAN_THREAD_LOG = "trace_span_thread_log",
  PROMPT_CREATE = "prompt_create",
  PROMPT_DELETE = "prompt_delete",
  OPTIMIZATION_RUN_DELETE = "optimization_run_delete",
  WORKSPACE_SETTINGS_CONFIGURE = "workspace_settings_configure",
  AI_PROVIDER_UPDATE = "ai_provider_update",
  COMMENT_WRITE = "comment_write",
  ONLINE_EVALUATION_RULE_UPDATE = "online_evaluation_rule_update",
  ALERT_UPDATE = "alert_update",
  PLAYGROUND_USE = "playground_use",
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

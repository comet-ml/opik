export interface User {
  apiKeys: string[];
  defaultWorkspace: string;
  email: string;
  getTeams: GetTeams;
  gitHub: boolean;
  loggedIn: boolean;
  profileImages: ProfileImages;
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

export enum ORGANIZATION_ROLE_TYPE {
  admin = "ADMIN",
  member = "MEMBER",
  opik = "LLM_ONLY",
  viewOnly = "VIEW_ONLY_MEMBER",
}

export interface Organization {
  id: string;
  name: string;
  paymentPlan: string;
  role: ORGANIZATION_ROLE_TYPE;
}

export interface UserPermission {
  permissionName:
    | "management"
    | "invite_users_to_workspace"
    | "project_visibility";
  permissionValue: "true" | "false";
}

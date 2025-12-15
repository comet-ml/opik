export const WORKSPACE_OWNER_VALUE = "true";
export const WORKSPACE_MEMBER_VALUE = "false";

export const CANNOT_CHANGE_MY_ROLE_IN_WS_TOOLTIP =
  "You can't update your own role";
export const CANNOT_CHANGE_ORG_ADMIN_ROLE_IN_WS_TOOLTIP =
  "You can't change the role, since this user is an organization admin";

export const MANAGEMENT_PERMISSIONS = {
  INVITE_USERS_FROM_ORGANIZATION: "invite-users-from-organization",
  INVITE_USERS_OUT_OF_ORGANIZATION: "invite-users-out-of-organization",
  CHANGE_WORKSPACE_ROLE: "change-workspace-role",
  CHANGE_WORKSPACE_ROLE_FOR_YOURSELF: "change-workspace-role-for-yourself",
} as const;

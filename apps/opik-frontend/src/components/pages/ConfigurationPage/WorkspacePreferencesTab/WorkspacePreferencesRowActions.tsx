import React from "react";
import { WorkspacePreference } from "@/components/pages/ConfigurationPage/WorkspacePreferencesTab/types";
import { RowActionsButtons } from "@/components/shared/DataTable/RowActionsButtons";

type WorkspacePreferencesRowActionsProps = {
  preference: WorkspacePreference;
  onEdit: (row: WorkspacePreference) => void;
};

const WorkspacePreferencesRowActions: React.FC<
  WorkspacePreferencesRowActionsProps
> = ({ preference, onEdit }) => {
  const handleEdit = () => {
    onEdit(preference);
  };

  return (
    <RowActionsButtons actions={[{ type: "edit", onClick: handleEdit }]} />
  );
};

export default WorkspacePreferencesRowActions;

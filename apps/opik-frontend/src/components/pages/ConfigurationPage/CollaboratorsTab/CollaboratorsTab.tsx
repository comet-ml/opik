import React, { useMemo } from "react";
import DataTable from "@/components/shared/DataTable/DataTable";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";

type Collaborator = {
  id: string;
  name: string;
  email: string;
  joined: string;
  workspaceRole: string;
};

const DEFAULT_COLUMNS: ColumnData<Collaborator>[] = [
  {
    id: "name",
    label: "Name / User name",
    type: COLUMN_TYPE.string,
  },
  {
    id: "email",
    label: "Email",
    type: COLUMN_TYPE.string,
  },
  {
    id: "joined",
    label: "Joined",
    type: COLUMN_TYPE.time,
  },
  {
    id: "workspaceRole",
    label: "Workspace role",
    type: COLUMN_TYPE.category,
  },
];

const CollaboratorsTab = () => {
  const columns = useMemo(() => {
    return convertColumnDataToColumn<Collaborator, Collaborator>(
      DEFAULT_COLUMNS,
      {},
    );
  }, []);

  return <DataTable columns={columns} data={[]} />;
};

export default CollaboratorsTab;

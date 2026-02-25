import React, { useMemo } from "react";

import { convertColumnDataToColumn } from "@/lib/table";
import DataTable from "@/components/shared/DataTable/DataTable";
import Loader from "@/components/shared/Loader/Loader";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import useLatestBlueprint from "@/api/optimizer-configs/useLatestBlueprint";
import { EnrichedBlueprintValue } from "@/types/optimizer-configs";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import BlueprintTypeCell from "./BlueprintTypeCell";
import BlueprintValueCell from "./BlueprintValueCell";

const COLUMNS: ColumnData<EnrichedBlueprintValue>[] = [
  {
    id: "key",
    label: "Key",
    type: COLUMN_TYPE.string,
  },
  {
    id: "type",
    label: "Type",
    type: COLUMN_TYPE.string,
    cell: BlueprintTypeCell as never,
  },
  {
    id: "value",
    label: "Value",
    type: COLUMN_TYPE.string,
    cell: BlueprintValueCell as never,
  },
];

type ConfigurationTabProps = {
  projectId: string;
};

const ConfigurationTab: React.FC<ConfigurationTabProps> = ({ projectId }) => {
  const { data: blueprint, isPending } = useLatestBlueprint({ projectId });

  const rows = useMemo(() => blueprint?.values ?? [], [blueprint?.values]);

  const columns = useMemo(
    () =>
      convertColumnDataToColumn<EnrichedBlueprintValue, EnrichedBlueprintValue>(
        COLUMNS,
        {},
      ),
    [],
  );

  if (isPending) {
    return <Loader />;
  }

  return (
    <PageBodyStickyContainer
      className="py-4"
      direction="horizontal"
      limitWidth
    >
      <DataTable columns={columns} data={rows} />
    </PageBodyStickyContainer>
  );
};

export default ConfigurationTab;

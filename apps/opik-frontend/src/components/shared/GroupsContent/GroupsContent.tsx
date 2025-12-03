import React, { useCallback, useMemo } from "react";
import filter from "lodash/filter";
import uniq from "lodash/uniq";
import { Group, GroupRowConfig, Groups } from "@/types/groups";
import { ColumnData, OnChangeFn } from "@/types/shared";
import GroupRow from "@/components/shared/GroupsButton/GroupRow";
import { cn } from "@/lib/utils";

type GroupsContentProps<TColumnData> = {
  groups: Groups;
  setGroups: OnChangeFn<Groups>;
  columns: ColumnData<TColumnData>[];
  config?: { rowsMap: Record<string, GroupRowConfig> };
  className?: string;
};

const GroupsContent = <TColumnData,>({
  groups,
  setGroups,
  columns,
  config,
  className,
}: GroupsContentProps<TColumnData>) => {
  const onRemoveRow = useCallback(
    (id: string) => {
      setGroups((prev) => filter(prev, (g) => g.id !== id));
    },
    [setGroups],
  );

  const onChangeRow = useCallback(
    (updateGroup: Group) => {
      setGroups((prev) =>
        prev.map((g) => (updateGroup.id === g.id ? updateGroup : g)),
      );
    },
    [setGroups],
  );

  const disabledColumns = useMemo(() => {
    const disposableColumns = columns
      .filter((c) => c.disposable)
      .map((c) => c.id);

    if (!disposableColumns.length) {
      return undefined;
    }

    const columnsWithGroups = uniq(groups.map((g) => g.field));

    return disposableColumns.filter((c) => columnsWithGroups.includes(c));
  }, [groups, columns]);

  const getConfig = useCallback(
    (field: string) => config?.rowsMap[field],
    [config],
  );

  const renderGroups = () => {
    return groups.map((group, index) => {
      const prefix = index === 0 ? "By" : "And";

      return (
        <GroupRow
          key={group.id}
          columns={columns}
          getConfig={getConfig}
          disabledColumns={disabledColumns}
          group={group}
          prefix={prefix}
          onRemove={onRemoveRow}
          onChange={onChangeRow}
          disabledSorting={true}
        />
      );
    });
  };

  return (
    <div className={cn("overflow-y-auto overflow-x-hidden py-4", className)}>
      <table className="table-auto">
        <tbody>{renderGroups()}</tbody>
      </table>
    </div>
  );
};

export default GroupsContent;

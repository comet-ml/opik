import { useMemo } from "react";
import { CellContext } from "@tanstack/react-table";
import { ProjectWithStatistic } from "@/types/projects";
import { PROJECT_ICON_COUNT } from "@/constants/projectIcons";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import ProjectIcon from "@/shared/ProjectIcon/ProjectIcon";
import LinkifyText from "@/shared/LinkifyText/LinkifyText";

const ProjectNameCell = (
  context: CellContext<ProjectWithStatistic, string>,
) => {
  const value = context.getValue();
  const projectId = context.row.original.id;
  const data = context.table.options.data;

  const iconIndices = useMemo(() => {
    const sorted = [...data].sort(
      (a, b) =>
        new Date(a.created_at).getTime() - new Date(b.created_at).getTime(),
    );
    return new Map(sorted.map((p, i) => [p.id, i % PROJECT_ICON_COUNT]));
  }, [data]);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <ProjectIcon index={iconIndices.get(projectId) ?? 0} />
      <span className="ml-2 truncate">
        <LinkifyText>{value}</LinkifyText>
      </span>
    </CellWrapper>
  );
};

export default ProjectNameCell;

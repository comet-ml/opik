import { CellContext } from "@tanstack/react-table";
import { ProjectWithStatistic } from "@/types/projects";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import ProjectAvatar from "@/shared/ProjectIcon/ProjectAvatar";
import LinkifyText from "@/shared/LinkifyText/LinkifyText";

const ProjectNameCell = (
  context: CellContext<ProjectWithStatistic, string>,
) => {
  const value = context.getValue();
  const projectId = context.row.original.id;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <ProjectAvatar projectId={projectId} />
      <span className="ml-2 truncate">
        <LinkifyText>{value}</LinkifyText>
      </span>
    </CellWrapper>
  );
};

export default ProjectNameCell;

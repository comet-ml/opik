import { CellContext } from "@tanstack/react-table";
import { ProjectWithStatistic } from "@/types/projects";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import ProjectIcon from "@/shared/ProjectIcon/ProjectIcon";
import LinkifyText from "@/shared/LinkifyText/LinkifyText";
import useProjectIconIndices from "@/hooks/useProjectIconIndex";

const ProjectNameCell = (
  context: CellContext<ProjectWithStatistic, string>,
) => {
  const value = context.getValue();
  const projectId = context.row.original.id;
  const iconIndices = useProjectIconIndices();

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

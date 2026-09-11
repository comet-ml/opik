import ProjectIcon from "@/shared/ProjectIcon/ProjectIcon";
import useProjectIconIndices from "@/hooks/useProjectIconIndex";

interface ProjectAvatarProps {
  projectId?: string | null;
  size?: "sm" | "md" | "lg";
}

const ProjectAvatar = ({ projectId, size = "sm" }: ProjectAvatarProps) => {
  const iconIndices = useProjectIconIndices();
  const index = projectId ? iconIndices.get(projectId) ?? 0 : 0;

  return <ProjectIcon index={index} size={size} />;
};

export default ProjectAvatar;

import useProjectById from "@/api/projects/useProjectById";

// The panel knows only the project id. The prompt names the project so a
// developer can tell at a glance it points at their own; the id stands in while
// the name resolves.
const useMcpPromptContext = (projectId: string) => {
  // refetchOnMount is off because LogsPage already holds this query; without it
  // opening the card would fire a second request for a name it already has.
  const { data: project } = useProjectById(
    { projectId },
    { enabled: Boolean(projectId), refetchOnMount: false },
  );

  return { projectName: project?.name ?? projectId };
};

export default useMcpPromptContext;

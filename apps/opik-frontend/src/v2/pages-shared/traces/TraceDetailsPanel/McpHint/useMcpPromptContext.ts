import useProjectById from "@/api/projects/useProjectById";

/**
 * Resolves the project's name, which the details panel does not carry — it only
 * knows the id.
 *
 * The prompt names the project rather than identifying it by UUID on purpose: a
 * prompt carrying two UUIDs is unreadable, and a developer reading it before
 * pressing Enter should be able to tell at a glance that it points at their own
 * project. While it resolves, the id stands in, so the prompt is never wrong —
 * only less readable.
 */
const useMcpPromptContext = (projectId: string) => {
  const { data: project } = useProjectById(
    { projectId },
    { enabled: Boolean(projectId) },
  );

  return { projectName: project?.name ?? projectId };
};

export default useMcpPromptContext;

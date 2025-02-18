export const getUIUrl = (host: string) => {
  return host.replace("/api", "");
};

export const getProjectUrl = ({
  host,
  projectName,
  workspaceName,
}: {
  host: string;
  projectName: string;
  workspaceName: string;
}) => {
  const encodedProjectName = encodeURIComponent(projectName);
  return `${getUIUrl(host)}/${workspaceName}/redirect/projects?name=${encodedProjectName}`;
};

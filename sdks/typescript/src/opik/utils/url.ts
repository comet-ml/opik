export const getUIUrl = (host: string) => {
  return host.replace("/api", "");
};

export const getProjectUrl = ({
  apiUrl,
  projectName,
  workspaceName,
}: {
  apiUrl: string;
  projectName: string;
  workspaceName: string;
}) => {
  const encodedProjectName = encodeURIComponent(projectName);
  return `${getUIUrl(apiUrl)}/${workspaceName}/redirect/projects?name=${encodedProjectName}`;
};

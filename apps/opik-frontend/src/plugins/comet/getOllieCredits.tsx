import api from "./api";

const getOllieCredits = async (
  workspaceName: string,
  signal?: AbortSignal,
): Promise<boolean> => {
  const { data } = await api.post<{ hasCredits: boolean }>(
    "/opik/ollie/credits",
    { workspaceName },
    { signal },
  );
  return data.hasCredits;
};

export default getOllieCredits;

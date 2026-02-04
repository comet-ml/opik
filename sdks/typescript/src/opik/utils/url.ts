/**
 * URL helper functions for the Opik TypeScript SDK
 */

/**
 * URL path and endpoint constants
 */
export const URL_PATHS = {
  REDIRECT_BASE: "v1/session/redirect",
  EXPERIMENTS: "api/v1/session/redirect/experiments/",
  PROJECTS: "api/v1/session/redirect/projects/",
  DATASETS: "api/v1/session/redirect/datasets/",
  PROJECT_BY_NAME: "redirect/projects",
};

/**
 * URL query parameter keys
 */
export const URL_PARAMS = {
  EXPERIMENT_ID: "experiment_id",
  DATASET_ID: "dataset_id",
  TRACE_ID: "trace_id",
  PROJECT_NAME: "name",
  PATH: "path",
  WORKSPACE: "workspace",
};

/**
 * Creates URL-safe query parameters with special character preservation
 * @param params - Object containing key-value pairs for the query
 * @returns URL-encoded query string with preserved special characters
 */
const createUrlSafeParams = (params: Record<string, string>): string => {
  return Object.entries(params)
    .map(([key, value]) => `${key}=${encodeURIComponent(value)}`)
    .join("&")
    .replace(/%3A/g, ":")
    .replace(/%2F/g, "/")
    .replace(/%26/g, "&")
    .replace(/%3F/g, "?")
    .replace(/%3D/g, "=");
};

/**
 * Gets the UI URL from the API URL
 * @param apiUrl - The API URL
 * @returns The UI URL with a trailing slash
 */
export const getUIUrl = (apiUrl: string): string => {
  return apiUrl.replace(/\/api$/, "");
};

/**
 * Creates a base64 encoded URL for Opik redirects
 * @param url - The URL to encode
 * @returns The base64 encoded URL
 */
const encodeOpikUrl = (url: string): string => {
  return Buffer.from(url).toString("base64");
};

/**
 * Creates a full URL from a base URL and path with query parameters
 * @param baseUrl - The base URL
 * @param path - The path to append
 * @param queryParams - The query parameters to include
 * @returns The complete URL
 */
const createUrl = (
  baseUrl: string,
  path: string,
  queryParams: Record<string, string>
): string => {
  const queryString = createUrlSafeParams(queryParams);
  const fullPath = `${path}?${queryString}`;
  return new URL(fullPath, baseUrl).toString();
};

/**
 * Constructs a URL for an experiment by its ID
 * @param datasetId - The dataset ID
 * @param experimentId - The experiment ID
 * @param baseUrl - The base URL to use
 * @returns The full URL to the experiment
 */
export const getExperimentUrlById = ({
  datasetId,
  experimentId,
  baseUrl,
}: {
  datasetId: string;
  experimentId: string;
  baseUrl: string;
}): string => {
  return createUrl(baseUrl, URL_PATHS.EXPERIMENTS, {
    [URL_PARAMS.EXPERIMENT_ID]: experimentId,
    [URL_PARAMS.DATASET_ID]: datasetId,
    [URL_PARAMS.PATH]: encodeOpikUrl(baseUrl),
  });
};

/**
 * Constructs a URL for a project by trace ID
 * @param traceId - The trace ID
 * @param baseUrl - The base URL to use
 * @returns The full URL to the project
 */
export const getProjectUrlByTraceId = (
  traceId: string,
  baseUrl: string
): string => {
  return createUrl(baseUrl, URL_PATHS.PROJECTS, {
    [URL_PARAMS.TRACE_ID]: traceId,
    [URL_PARAMS.PATH]: encodeOpikUrl(baseUrl),
  });
};

/**
 * Constructs a URL for a dataset by its ID
 * @param datasetId - The dataset ID
 * @param baseUrl - The base URL to use
 * @returns The full URL to the dataset
 */
export const getDatasetUrlById = (
  datasetId: string,
  baseUrl: string
): string => {
  return createUrl(baseUrl, URL_PATHS.DATASETS, {
    [URL_PARAMS.DATASET_ID]: datasetId,
    [URL_PARAMS.PATH]: encodeOpikUrl(baseUrl),
  });
};

/**
 * Constructs a URL for a project by its name and workspace
 * @param projectName - The name of the project
 * @param workspaceName - The workspace name
 * @param apiUrl - The API URL to use
 * @returns The full URL to the project
 */
export const getProjectUrl = ({
  projectName,
  workspaceName,
  apiUrl,
}: {
  projectName: string;
  workspaceName: string;
  apiUrl: string;
}): string => {
  const uiBaseUrl = getUIUrl(apiUrl);
  const path = `${workspaceName}/${URL_PATHS.PROJECT_BY_NAME}`;
  return createUrl(uiBaseUrl, path, {
    [URL_PARAMS.PROJECT_NAME]: projectName,
  });
};

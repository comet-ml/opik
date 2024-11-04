import { UseQueryOptions } from "@tanstack/react-query";
import axios from "axios";

const BASE_API_URL = import.meta.env.VITE_BASE_API_URL || "/api";
const axiosInstance = axios.create({
  baseURL: BASE_API_URL,
});

axiosInstance.defaults.withCredentials = true;

export const PROJECTS_REST_ENDPOINT = "/v1/private/projects/";
export const DATASETS_REST_ENDPOINT = "/v1/private/datasets/";
export const EXPERIMENTS_REST_ENDPOINT = "/v1/private/experiments/";
export const FEEDBACK_DEFINITIONS_REST_ENDPOINT =
  "/v1/private/feedback-definitions/";
export const TRACES_REST_ENDPOINT = "/v1/private/traces/";
export const SPANS_REST_ENDPOINT = "/v1/private/spans/";
export const PROMPTS_REST_ENDPOINT = "/v1/private/prompts/";

export type QueryConfig<TQueryFnData, TData = TQueryFnData> = Omit<
  UseQueryOptions<
    TQueryFnData,
    Error,
    TData,
    [string, Record<string, unknown>, ...string[]]
  >,
  "queryKey" | "queryFn"
>;

export default axiosInstance;

import { UseQueryOptions } from "@tanstack/react-query";
import axios from "axios";

const BASE_COMET_API_URL = import.meta.env.VITE_BASE_COMET_API_URL || "/api";
const axiosInstance = axios.create({
  baseURL: BASE_COMET_API_URL,
});
axiosInstance.defaults.withCredentials = true;

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

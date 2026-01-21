import { DATASET_EXPORT_STATUS } from "@/types/datasets";

export const isJobLoading = (status: DATASET_EXPORT_STATUS) =>
  status === DATASET_EXPORT_STATUS.PENDING ||
  status === DATASET_EXPORT_STATUS.PROCESSING;

export const isJobCompleted = (status: DATASET_EXPORT_STATUS) =>
  status === DATASET_EXPORT_STATUS.COMPLETED;

export const isJobFailed = (status: DATASET_EXPORT_STATUS) =>
  status === DATASET_EXPORT_STATUS.FAILED;

export const isTerminalStatus = (status?: DATASET_EXPORT_STATUS) =>
  status === DATASET_EXPORT_STATUS.COMPLETED ||
  status === DATASET_EXPORT_STATUS.FAILED;

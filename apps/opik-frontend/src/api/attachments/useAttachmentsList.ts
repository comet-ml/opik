import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  ATTACHMENTS_REST_ENDPOINT,
  BASE_API_URL,
  QueryConfig,
} from "@/api/api";
import { Attachment, AttachmentEntityType } from "@/types/attachments";

type UseAttachmentsListParams = {
  projectId: string;
  id: string;
  type: AttachmentEntityType;
  page: number;
  size: number;
};

export type UseAttachmentsListResponse = {
  content: Attachment[];
  total: number;
};

const getAttachmentsList = async (
  { signal }: QueryFunctionContext,
  { projectId, id, type, size, page }: UseAttachmentsListParams,
  path: string,
) => {
  const { data } = await api.get(`${ATTACHMENTS_REST_ENDPOINT}list`, {
    signal,
    params: {
      project_id: projectId,
      entity_id: id,
      entity_type: type,
      path,
      size,
      page,
    },
  });

  return data;
};

export default function useAttachmentsList(
  params: UseAttachmentsListParams,
  options?: QueryConfig<UseAttachmentsListResponse>,
) {
  const path = btoa(new URL(BASE_API_URL, location.origin).toString());
  return useQuery({
    queryKey: ["attachments", params],
    queryFn: (context) => getAttachmentsList(context, params, path),
    ...options,
  });
}

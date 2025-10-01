import axios from "axios";

export async function fetchTraces(filters: { hasComment?: boolean } = {}) {
  const { data } = await axios.get("/api/traces", {
    params: { has_comment: filters.hasComment },
  });
  return data;
}
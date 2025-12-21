import { useMediaQuery } from "./useMediaQuery";
import { QUERY_CAN_HOVER } from "@/constants/responsiveness";

export const useCanHover = (): boolean => useMediaQuery(QUERY_CAN_HOVER);

import { useMediaQuery } from "./useMediaQuery";
import {
  QUERY_IS_PHONE_PORTRAIT,
  QUERY_IS_PHONE_LANDSCAPE,
} from "@/constants/responsiveness";

interface PhoneState {
  /** True if the device is a phone in either orientation */
  isPhone: boolean;
  /** True only if phone is upright (portrait) */
  isPhonePortrait: boolean;
  /** True only if phone is sideways (landscape) */
  isPhoneLandscape: boolean;
}

export const useIsPhone = (): PhoneState => {
  const isPhonePortrait = useMediaQuery(QUERY_IS_PHONE_PORTRAIT);
  const isPhoneLandscape = useMediaQuery(QUERY_IS_PHONE_LANDSCAPE);

  return {
    isPhone: isPhonePortrait || isPhoneLandscape,
    isPhonePortrait,
    isPhoneLandscape,
  };
};

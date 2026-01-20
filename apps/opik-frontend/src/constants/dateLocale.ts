/**
 * Mapping of timezone prefixes/names to their most likely locale.
 * This provides geographic-based locale detection
 */
export const TIMEZONE_TO_LOCALE: Record<string, string> = {
  // Europe
  "Europe/London": "en-GB",
  "Europe/Dublin": "en-IE",
  "Europe/Paris": "fr-FR",
  "Europe/Berlin": "de-DE",
  "Europe/Vienna": "de-AT",
  "Europe/Zurich": "de-CH",
  "Europe/Amsterdam": "nl-NL",
  "Europe/Brussels": "nl-BE",
  "Europe/Rome": "it-IT",
  "Europe/Madrid": "es-ES",
  "Europe/Lisbon": "pt-PT",
  "Europe/Warsaw": "pl-PL",
  "Europe/Prague": "cs-CZ",
  "Europe/Budapest": "hu-HU",
  "Europe/Bucharest": "ro-RO",
  "Europe/Sofia": "bg-BG",
  "Europe/Athens": "el-GR",
  "Europe/Helsinki": "fi-FI",
  "Europe/Stockholm": "sv-SE",
  "Europe/Oslo": "nb-NO",
  "Europe/Copenhagen": "da-DK",
  "Europe/Moscow": "ru-RU",
  "Europe/Kiev": "uk-UA",
  "Europe/Kyiv": "uk-UA",
  "Europe/Istanbul": "tr-TR",
  "Europe/Vilnius": "lt-LT",
  "Europe/Riga": "lv-LV",
  "Europe/Tallinn": "et-EE",
  "Europe/Bratislava": "sk-SK",
  "Europe/Ljubljana": "sl-SI",
  "Europe/Zagreb": "hr-HR",
  "Europe/Belgrade": "sr-RS",
  "Europe/Minsk": "be-BY",

  // Americas
  "America/New_York": "en-US",
  "America/Chicago": "en-US",
  "America/Denver": "en-US",
  "America/Los_Angeles": "en-US",
  "America/Phoenix": "en-US",
  "America/Anchorage": "en-US",
  "America/Toronto": "en-CA",
  "America/Vancouver": "en-CA",
  "America/Montreal": "fr-CA",
  "America/Mexico_City": "es-MX",
  "America/Sao_Paulo": "pt-BR",
  "America/Buenos_Aires": "es-AR",
  "America/Santiago": "es-CL",
  "America/Lima": "es-PE",
  "America/Bogota": "es-CO",
  "America/Caracas": "es-VE",

  // Asia
  "Asia/Tokyo": "ja-JP",
  "Asia/Seoul": "ko-KR",
  "Asia/Shanghai": "zh-CN",
  "Asia/Hong_Kong": "zh-HK",
  "Asia/Taipei": "zh-TW",
  "Asia/Singapore": "en-SG",
  "Asia/Bangkok": "th-TH",
  "Asia/Jakarta": "id-ID",
  "Asia/Manila": "en-PH",
  "Asia/Kuala_Lumpur": "ms-MY",
  "Asia/Ho_Chi_Minh": "vi-VN",
  "Asia/Kolkata": "en-IN",
  "Asia/Mumbai": "en-IN",
  "Asia/Dubai": "ar-AE",
  "Asia/Riyadh": "ar-SA",
  "Asia/Jerusalem": "he-IL",
  "Asia/Tel_Aviv": "he-IL",

  // Oceania
  "Australia/Sydney": "en-AU",
  "Australia/Melbourne": "en-AU",
  "Australia/Brisbane": "en-AU",
  "Australia/Perth": "en-AU",
  "Pacific/Auckland": "en-NZ",

  // Africa
  "Africa/Cairo": "ar-EG",
  "Africa/Johannesburg": "en-ZA",
  "Africa/Lagos": "en-NG",
  "Africa/Nairobi": "en-KE",
  "Africa/Casablanca": "ar-MA",
};

/**
 * Mapping of locale patterns to their dayjs parse formats.
 * Grouped by the date format style they produce.
 */
export const LOCALE_TO_PARSE_FORMATS: Record<string, string[]> = {
  // US style: M/D/YYYY, h:mm AM/PM (en-US, en-CA, etc.)
  "en-US": [
    "M/D/YYYY, h:mm A",
    "M/D/YYYY, h:mm:ss A",
    "MM/DD/YYYY, h:mm A",
    "MM/DD/YYYY, h:mm:ss A",
    "M/D/YYYY h:mm A",
    "MM/DD/YY hh:mm A",
    "MM/DD/YYYY hh:mm A",
  ],
  // UK/Ireland style: DD/MM/YYYY, HH:mm (en-GB, en-IE, en-AU, en-NZ, etc.)
  "en-GB": ["DD/MM/YYYY, HH:mm", "DD/MM/YYYY, HH:mm:ss", "DD/MM/YYYY HH:mm"],
  // German/Austrian/Swiss style: DD.MM.YYYY, HH:mm
  "de-DE": ["DD.MM.YYYY, HH:mm", "DD.MM.YYYY, HH:mm:ss", "DD.MM.YYYY HH:mm"],
  // Japanese/Korean/Chinese style: YYYY/MM/DD HH:mm
  "ja-JP": [
    "YYYY/MM/DD H:mm",
    "YYYY/MM/DD HH:mm",
    "YYYY/MM/DD H:mm:ss",
    "YYYY/MM/DD HH:mm:ss",
  ],
  // French style: DD/MM/YYYY HH:mm (similar to UK but sometimes with different separators)
  "fr-FR": [
    "DD/MM/YYYY, HH:mm",
    "DD/MM/YYYY, HH:mm:ss",
    "DD/MM/YYYY HH:mm",
    "DD/MM/YYYY à HH:mm",
  ],
  // Spanish style: DD/MM/YYYY, HH:mm
  "es-ES": ["DD/MM/YYYY, H:mm", "DD/MM/YYYY, H:mm:ss", "DD/MM/YYYY H:mm"],
  // Polish style: DD.MM.YYYY, HH:mm
  "pl-PL": ["DD.MM.YYYY, HH:mm", "DD.MM.YYYY, HH:mm:ss", "DD.MM.YYYY HH:mm"],
  // Russian style: DD.MM.YYYY, HH:mm
  "ru-RU": ["DD.MM.YYYY, HH:mm", "DD.MM.YYYY, HH:mm:ss", "DD.MM.YYYY HH:mm"],
  // Portuguese (Brazil) style: DD/MM/YYYY HH:mm
  "pt-BR": ["DD/MM/YYYY, HH:mm", "DD/MM/YYYY, HH:mm:ss", "DD/MM/YYYY HH:mm"],
  // Italian style: DD/MM/YYYY, HH:mm
  "it-IT": ["DD/MM/YYYY, HH:mm", "DD/MM/YYYY, HH:mm:ss", "DD/MM/YYYY HH:mm"],
  // Dutch style: DD-MM-YYYY HH:mm
  "nl-NL": ["DD-MM-YYYY, HH:mm", "DD-MM-YYYY, HH:mm:ss", "DD-MM-YYYY HH:mm"],
  // Swedish style: YYYY-MM-DD HH:mm
  "sv-SE": ["YYYY-MM-DD, HH:mm", "YYYY-MM-DD, HH:mm:ss", "YYYY-MM-DD HH:mm"],
  // Chinese style: YYYY/MM/DD HH:mm
  "zh-CN": [
    "YYYY/MM/DD H:mm",
    "YYYY/MM/DD HH:mm",
    "YYYY/MM/DD H:mm:ss",
    "YYYY/MM/DD HH:mm:ss",
  ],
  // Korean style: YYYY. MM. DD. HH:mm
  "ko-KR": [
    "YYYY. MM. DD. HH:mm",
    "YYYY. MM. DD. HH:mm:ss",
    "YYYY/MM/DD HH:mm",
  ],
};

/**
 * Mapping of locale patterns to their placeholder format strings.
 * These are user-friendly format hints shown in input fields.
 */
export const LOCALE_TO_PLACEHOLDER: Record<string, string> = {
  "en-US": "MM/DD/YYYY, hh:mm AM/PM",
  "en-GB": "DD/MM/YYYY, HH:mm",
  "en-AU": "DD/MM/YYYY, HH:mm",
  "en-NZ": "DD/MM/YYYY, HH:mm",
  "en-IE": "DD/MM/YYYY, HH:mm",
  "de-DE": "DD.MM.YYYY, HH:mm",
  "de-AT": "DD.MM.YYYY, HH:mm",
  "de-CH": "DD.MM.YYYY, HH:mm",
  "ja-JP": "YYYY/MM/DD HH:mm",
  "zh-CN": "YYYY/MM/DD HH:mm",
  "zh-TW": "YYYY/MM/DD HH:mm",
  "zh-HK": "YYYY/MM/DD HH:mm",
  "ko-KR": "YYYY. MM. DD. HH:mm",
  "fr-FR": "DD/MM/YYYY, HH:mm",
  "fr-CA": "DD/MM/YYYY, HH:mm",
  "es-ES": "DD/MM/YYYY, HH:mm",
  "es-MX": "DD/MM/YYYY, HH:mm",
  "it-IT": "DD/MM/YYYY, HH:mm",
  "pt-BR": "DD/MM/YYYY, HH:mm",
  "pt-PT": "DD/MM/YYYY, HH:mm",
  "nl-NL": "DD-MM-YYYY, HH:mm",
  "nl-BE": "DD/MM/YYYY, HH:mm",
  "pl-PL": "DD.MM.YYYY, HH:mm",
  "ru-RU": "DD.MM.YYYY, HH:mm",
  "sv-SE": "YYYY-MM-DD, HH:mm",
  "da-DK": "DD.MM.YYYY, HH:mm",
  "nb-NO": "DD.MM.YYYY, HH:mm",
  "fi-FI": "DD.MM.YYYY, HH:mm",
  "tr-TR": "DD.MM.YYYY, HH:mm",
  "uk-UA": "DD.MM.YYYY, HH:mm",
  "cs-CZ": "DD.MM.YYYY, HH:mm",
  "hu-HU": "YYYY.MM.DD, HH:mm",
  "ro-RO": "DD.MM.YYYY, HH:mm",
  "bg-BG": "DD.MM.YYYY, HH:mm",
  "el-GR": "DD/MM/YYYY, HH:mm",
  "he-IL": "DD.MM.YYYY, HH:mm",
  "ar-SA": "DD/MM/YYYY, HH:mm",
  "ar-AE": "DD/MM/YYYY, HH:mm",
  "th-TH": "DD/MM/YYYY, HH:mm",
  "vi-VN": "DD/MM/YYYY, HH:mm",
  "id-ID": "DD/MM/YYYY, HH:mm",
  "ms-MY": "DD/MM/YYYY, HH:mm",
};

export const DEFAULT_LOCALE = "en-US";

export const DEFAULT_DATE_PLACEHOLDER = "MM/DD/YYYY, hh:mm AM/PM";

export const FALLBACK_DATE_FORMATS = {
  withSeconds: "MMM DD, YYYY h:mm:ss A",
  withoutSeconds: "MMM DD, YYYY h:mm A",
};

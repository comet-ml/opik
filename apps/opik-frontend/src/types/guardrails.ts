export enum GuardrailTypes {
  TOPIC = "TOPIC",
  PII = "PII",
}

export enum GuardrailResult {
  PASSED = "passed",
  FAILED = "failed",
}

export enum PiiSupportedEntities {
  CREDIT_CARD = "CREDIT_CARD",
  CRYPTO = "CRYPTO",
  DATE_TIME = "DATE_TIME",
  EMAIL_ADDRESS = "EMAIL_ADDRESS",
  IBAN_CODE = "IBAN_CODE",
  IP_ADDRESS = "IP_ADDRESS",
  NRP = "NRP",
  LOCATION = "LOCATION",
  PERSON = "PERSON",
  PHONE_NUMBER = "PHONE_NUMBER",
  MEDICAL_LICENSE = "MEDICAL_LICENSE",
  URL = "URL",

  US_BANK_NUMBER = "US_BANK_NUMBER",
  US_DRIVER_LICENSE = "US_DRIVER_LICENSE",
  US_ITIN = "US_ITIN",
  US_PASSPORT = "US_PASSPORT",
  US_SSN = "US_SSN",

  UK_NHS = "UK_NHS",
  UK_NINO = "UK_NINO",
  UK_PASSPORT = "UK_PASSPORT",

  ES_NIF = "ES_NIF",
  ES_NIE = "ES_NIE",
  ES_DNI = "ES_DNI",
  ES_CIF = "ES_CIF",

  IT_FISCAL_CODE = "IT_FISCAL_CODE",

  PL_PESEL = "PL_PESEL",
  PL_NIP = "PL_NIP",
  PL_ID = "PL_ID",

  SG_NRIC_FIN = "SG_NRIC_FIN",

  AU_ABN = "AU_ABN",
  AU_ACN = "AU_ACN",
  AU_TFN = "AU_TFN",

  IN_AADHAAR = "IN_AADHAAR",
  IN_PAN = "IN_PAN",

  FI_HETU = "FI_HETU",
}

export type GuardrailValidationScore = {
  name: PiiSupportedEntities | string;
  score: number;
};

export type GuardrailValidationCheck = {
  name: GuardrailTypes;
  result: GuardrailResult;
  items: GuardrailValidationScore[];
};
export type GuardrailValidation = {
  span_id: string;
  checks: GuardrailValidationCheck[];
};
export type GuardrailComputedResult = {
  name: GuardrailTypes;
  status: GuardrailResult;
};

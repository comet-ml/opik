import {
  GuardrailComputedResult,
  GuardrailResult,
  GuardrailTypes,
  GuardrailValidation,
  PiiSupportedEntities,
} from "@/types/guardrails";

export const PIIEntitiesLabelMap = {
  [PiiSupportedEntities.CREDIT_CARD]: "Credit card number",
  [PiiSupportedEntities.PHONE_NUMBER]: "Phone number",
  [PiiSupportedEntities.EMAIL_ADDRESS]: "Email",
  [PiiSupportedEntities.IBAN_CODE]: "Bank account number",
  [PiiSupportedEntities.IP_ADDRESS]: "IP address",
  [PiiSupportedEntities.NRP]: "National Registration Plate",
  [PiiSupportedEntities.LOCATION]: "Address",
  [PiiSupportedEntities.PERSON]: "Name",
  [PiiSupportedEntities.CRYPTO]: "Cryptocurrency",
  [PiiSupportedEntities.MEDICAL_LICENSE]: "Medical license",
  [PiiSupportedEntities.URL]: "URL",
  [PiiSupportedEntities.DATE_TIME]: "Date time",
  [PiiSupportedEntities.US_BANK_NUMBER]: "US bank account number",
  [PiiSupportedEntities.US_DRIVER_LICENSE]: "US driver's license",
  [PiiSupportedEntities.US_ITIN]: "US ITIN",
  [PiiSupportedEntities.US_PASSPORT]: "US passport",
  [PiiSupportedEntities.US_SSN]: "SSN",
  [PiiSupportedEntities.UK_NHS]: "NHS number",
  [PiiSupportedEntities.UK_NINO]: "NINO",
  [PiiSupportedEntities.UK_PASSPORT]: "UK passport",
  [PiiSupportedEntities.ES_NIF]: "NIF",
  [PiiSupportedEntities.ES_NIE]: "NIE",
  [PiiSupportedEntities.ES_DNI]: "DNI",
  [PiiSupportedEntities.ES_CIF]: "CIF",
  [PiiSupportedEntities.IT_FISCAL_CODE]: "Fiscal code",
  [PiiSupportedEntities.PL_PESEL]: "PESEL",
  [PiiSupportedEntities.PL_NIP]: "NIP",
  [PiiSupportedEntities.PL_ID]: "Polish ID",
  [PiiSupportedEntities.SG_NRIC_FIN]: "Singapore NRIC/FIN",
  [PiiSupportedEntities.AU_ABN]: "Australian ABN",
  [PiiSupportedEntities.AU_ACN]: "Australian ACN",
  [PiiSupportedEntities.AU_TFN]: "Australian TFN",
  [PiiSupportedEntities.IN_AADHAAR]: "Indian Aadhaar",
  [PiiSupportedEntities.IN_PAN]: "Indian PAN",
  [PiiSupportedEntities.FI_HETU]: "Finnish HETU",
};

export const GuardrailNamesLabelMap = {
  [GuardrailTypes.TOPIC]: "Topic guardrail",
  [GuardrailTypes.PII]: "PII guardrail",
};

export const getGuardrailComputedResult = (
  guardrails: GuardrailValidation[],
) => {
  const uniqueNames = new Set<GuardrailTypes>();
  guardrails.forEach((item) => {
    item.checks.forEach((check) => {
      uniqueNames.add(check.name);
    });
  });

  const result = {} as Record<GuardrailTypes, GuardrailResult>;

  uniqueNames.forEach((name) => {
    result[name] = GuardrailResult.PASSED;

    for (const item of guardrails) {
      for (const check of item.checks) {
        if (check.name === name && check.result === GuardrailResult.FAILED) {
          result[name] = GuardrailResult.FAILED;
          break;
        }
      }
      if (result[name] === GuardrailResult.FAILED) break;
    }
  });

  const statusList = Object.entries(result).map(([name, status]) => ({
    name,
    status,
  })) as GuardrailComputedResult[];

  let generalStatus = GuardrailResult.PASSED;
  const hasFailedGuardrails = statusList.some(
    (res) => res.status === GuardrailResult.FAILED,
  );
  if (hasFailedGuardrails) {
    generalStatus = GuardrailResult.FAILED;
  }

  return { statusList, generalStatus };
};

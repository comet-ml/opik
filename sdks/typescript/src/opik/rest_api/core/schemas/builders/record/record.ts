import { MaybeValid, Schema, SchemaType, ValidationError } from "../../Schema";
import { entries } from "../../utils/entries";
import { getErrorMessageForIncorrectType } from "../../utils/getErrorMessageForIncorrectType";
import { isPlainObject } from "../../utils/isPlainObject";
import { maybeSkipValidation } from "../../utils/maybeSkipValidation";
import { getSchemaUtils } from "../schema-utils";
import { BaseRecordSchema, RecordSchema } from "./types";

export function record<RawKey extends string | number, RawValue, ParsedValue, ParsedKey extends string | number>(
    keySchema: Schema<RawKey, ParsedKey>,
    valueSchema: Schema<RawValue, ParsedValue>,
): RecordSchema<RawKey, RawValue, ParsedKey, ParsedValue> {
    const baseSchema: BaseRecordSchema<RawKey, RawValue, ParsedKey, ParsedValue> = {
        parse: (raw, opts) => {
            return validateAndTransformRecord({
                value: raw,
                isKeyNumeric: keySchema.getType() === SchemaType.NUMBER,
                transformKey: (key) =>
                    keySchema.parse(key, {
                        ...opts,
                        breadcrumbsPrefix: [...(opts?.breadcrumbsPrefix ?? []), `${key} (key)`],
                    }),
                transformValue: (value, key) =>
                    valueSchema.parse(value, {
                        ...opts,
                        breadcrumbsPrefix: [...(opts?.breadcrumbsPrefix ?? []), `${key}`],
                    }),
                breadcrumbsPrefix: opts?.breadcrumbsPrefix,
            });
        },
        json: (parsed, opts) => {
            return validateAndTransformRecord({
                value: parsed,
                isKeyNumeric: keySchema.getType() === SchemaType.NUMBER,
                transformKey: (key) =>
                    keySchema.json(key, {
                        ...opts,
                        breadcrumbsPrefix: [...(opts?.breadcrumbsPrefix ?? []), `${key} (key)`],
                    }),
                transformValue: (value, key) =>
                    valueSchema.json(value, {
                        ...opts,
                        breadcrumbsPrefix: [...(opts?.breadcrumbsPrefix ?? []), `${key}`],
                    }),
                breadcrumbsPrefix: opts?.breadcrumbsPrefix,
            });
        },
        getType: () => SchemaType.RECORD,
    };

    return {
        ...maybeSkipValidation(baseSchema),
        ...getSchemaUtils(baseSchema),
    };
}

function validateAndTransformRecord<TransformedKey extends string | number, TransformedValue>({
    value,
    isKeyNumeric,
    transformKey,
    transformValue,
    breadcrumbsPrefix = [],
}: {
    value: unknown;
    isKeyNumeric: boolean;
    transformKey: (key: string | number) => MaybeValid<TransformedKey>;
    transformValue: (value: unknown, key: string | number) => MaybeValid<TransformedValue>;
    breadcrumbsPrefix: string[] | undefined;
}): MaybeValid<Record<TransformedKey, TransformedValue>> {
    if (!isPlainObject(value)) {
        return {
            ok: false,
            errors: [
                {
                    path: breadcrumbsPrefix,
                    message: getErrorMessageForIncorrectType(value, "object"),
                },
            ],
        };
    }

    return entries(value).reduce<MaybeValid<Record<TransformedKey, TransformedValue>>>(
        (accPromise, [stringKey, value]) => {
            if (value === undefined) {
                return accPromise;
            }

            const acc = accPromise;

            let key: string | number = stringKey;
            if (isKeyNumeric) {
                const numberKey = stringKey.length > 0 ? Number(stringKey) : NaN;
                if (!isNaN(numberKey)) {
                    key = numberKey;
                }
            }
            const transformedKey = transformKey(key);

            const transformedValue = transformValue(value, key);

            if (acc.ok && transformedKey.ok && transformedValue.ok) {
                return {
                    ok: true,
                    value: {
                        ...acc.value,
                        [transformedKey.value]: transformedValue.value,
                    },
                };
            }

            const errors: ValidationError[] = [];
            if (!acc.ok) {
                errors.push(...acc.errors);
            }
            if (!transformedKey.ok) {
                errors.push(...transformedKey.errors);
            }
            if (!transformedValue.ok) {
                errors.push(...transformedValue.errors);
            }

            return {
                ok: false,
                errors,
            };
        },
        { ok: true, value: {} as Record<TransformedKey, TransformedValue> },
    );
}

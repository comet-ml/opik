import { type BaseSchema, type MaybeValid, SchemaType } from "../../Schema.js";
import { getErrorMessageForIncorrectType } from "../../utils/getErrorMessageForIncorrectType.js";
import { isPlainObject } from "../../utils/isPlainObject.js";
import { keys } from "../../utils/keys.js";
import { maybeSkipValidation } from "../../utils/maybeSkipValidation.js";
import { enum_ } from "../enum/index.js";
import type { ObjectSchema } from "../object/index.js";
import { getObjectLikeUtils, type ObjectLikeSchema } from "../object-like/index.js";
import { getSchemaUtils } from "../schema-utils/index.js";
import type { Discriminant } from "./discriminant.js";
import type {
    inferParsedDiscriminant,
    inferParsedUnion,
    inferRawDiscriminant,
    inferRawUnion,
    UnionSubtypes,
} from "./types.js";

export function union<D extends string | Discriminant<any, any>, U extends UnionSubtypes<any>>(
    discriminant: D,
    union: U,
): ObjectLikeSchema<inferRawUnion<D, U>, inferParsedUnion<D, U>> {
    const rawDiscriminant =
        typeof discriminant === "string" ? discriminant : (discriminant.rawDiscriminant as inferRawDiscriminant<D>);
    const parsedDiscriminant =
        typeof discriminant === "string"
            ? discriminant
            : (discriminant.parsedDiscriminant as inferParsedDiscriminant<D>);

    const discriminantValueSchema = enum_(keys(union) as string[]);

    const baseSchema: BaseSchema<inferRawUnion<D, U>, inferParsedUnion<D, U>> = {
        parse: (raw, opts) => {
            return transformAndValidateUnion({
                value: raw,
                discriminant: rawDiscriminant,
                transformedDiscriminant: parsedDiscriminant,
                transformDiscriminantValue: (discriminantValue) =>
                    discriminantValueSchema.parse(discriminantValue, {
                        allowUnrecognizedEnumValues: opts?.allowUnrecognizedUnionMembers,
                        breadcrumbsPrefix: [...(opts?.breadcrumbsPrefix ?? []), rawDiscriminant],
                    }),
                getAdditionalPropertiesSchema: (discriminantValue) => union[discriminantValue],
                allowUnrecognizedUnionMembers: opts?.allowUnrecognizedUnionMembers,
                transformAdditionalProperties: (additionalProperties, additionalPropertiesSchema) =>
                    additionalPropertiesSchema.parse(additionalProperties, opts),
                breadcrumbsPrefix: opts?.breadcrumbsPrefix,
            });
        },
        json: (parsed, opts) => {
            return transformAndValidateUnion({
                value: parsed,
                discriminant: parsedDiscriminant,
                transformedDiscriminant: rawDiscriminant,
                transformDiscriminantValue: (discriminantValue) =>
                    discriminantValueSchema.json(discriminantValue, {
                        allowUnrecognizedEnumValues: opts?.allowUnrecognizedUnionMembers,
                        breadcrumbsPrefix: [...(opts?.breadcrumbsPrefix ?? []), parsedDiscriminant],
                    }),
                getAdditionalPropertiesSchema: (discriminantValue) => union[discriminantValue],
                allowUnrecognizedUnionMembers: opts?.allowUnrecognizedUnionMembers,
                transformAdditionalProperties: (additionalProperties, additionalPropertiesSchema) =>
                    additionalPropertiesSchema.json(additionalProperties, opts),
                breadcrumbsPrefix: opts?.breadcrumbsPrefix,
            });
        },
        getType: () => SchemaType.UNION,
    };

    return {
        ...maybeSkipValidation(baseSchema),
        ...getSchemaUtils(baseSchema),
        ...getObjectLikeUtils(baseSchema),
    };
}

function transformAndValidateUnion<
    TransformedDiscriminant extends string,
    TransformedDiscriminantValue extends string,
    TransformedAdditionalProperties,
>({
    value,
    discriminant,
    transformedDiscriminant,
    transformDiscriminantValue,
    getAdditionalPropertiesSchema,
    allowUnrecognizedUnionMembers = false,
    transformAdditionalProperties,
    breadcrumbsPrefix = [],
}: {
    value: unknown;
    discriminant: string;
    transformedDiscriminant: TransformedDiscriminant;
    transformDiscriminantValue: (discriminantValue: unknown) => MaybeValid<TransformedDiscriminantValue>;
    getAdditionalPropertiesSchema: (discriminantValue: string) => ObjectSchema<any, any> | undefined;
    allowUnrecognizedUnionMembers: boolean | undefined;
    transformAdditionalProperties: (
        additionalProperties: unknown,
        additionalPropertiesSchema: ObjectSchema<any, any>,
    ) => MaybeValid<TransformedAdditionalProperties>;
    breadcrumbsPrefix: string[] | undefined;
}): MaybeValid<Record<TransformedDiscriminant, TransformedDiscriminantValue> & TransformedAdditionalProperties> {
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

    const { [discriminant]: discriminantValue, ...additionalProperties } = value;

    if (discriminantValue == null) {
        return {
            ok: false,
            errors: [
                {
                    path: breadcrumbsPrefix,
                    message: `Missing discriminant ("${discriminant}")`,
                },
            ],
        };
    }

    const transformedDiscriminantValue = transformDiscriminantValue(discriminantValue);
    if (!transformedDiscriminantValue.ok) {
        return {
            ok: false,
            errors: transformedDiscriminantValue.errors,
        };
    }

    const additionalPropertiesSchema = getAdditionalPropertiesSchema(transformedDiscriminantValue.value);

    if (additionalPropertiesSchema == null) {
        if (allowUnrecognizedUnionMembers) {
            return {
                ok: true,
                value: {
                    [transformedDiscriminant]: transformedDiscriminantValue.value,
                    ...additionalProperties,
                } as Record<TransformedDiscriminant, TransformedDiscriminantValue> & TransformedAdditionalProperties,
            };
        } else {
            return {
                ok: false,
                errors: [
                    {
                        path: [...breadcrumbsPrefix, discriminant],
                        message: "Unexpected discriminant value",
                    },
                ],
            };
        }
    }

    const transformedAdditionalProperties = transformAdditionalProperties(
        additionalProperties,
        additionalPropertiesSchema,
    );
    if (!transformedAdditionalProperties.ok) {
        return transformedAdditionalProperties;
    }

    return {
        ok: true,
        value: {
            [transformedDiscriminant]: discriminantValue,
            ...transformedAdditionalProperties.value,
        } as Record<TransformedDiscriminant, TransformedDiscriminantValue> & TransformedAdditionalProperties,
    };
}

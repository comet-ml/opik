import { BaseSchema, MaybeValid, Schema, SchemaType, ValidationError } from "../../Schema";
import { getErrorMessageForIncorrectType } from "../../utils/getErrorMessageForIncorrectType";
import { maybeSkipValidation } from "../../utils/maybeSkipValidation";
import { getSchemaUtils } from "../schema-utils";

export function list<Raw, Parsed>(schema: Schema<Raw, Parsed>): Schema<Raw[], Parsed[]> {
    const baseSchema: BaseSchema<Raw[], Parsed[]> = {
        parse: (raw, opts) =>
            validateAndTransformArray(raw, (item, index) =>
                schema.parse(item, {
                    ...opts,
                    breadcrumbsPrefix: [...(opts?.breadcrumbsPrefix ?? []), `[${index}]`],
                })
            ),
        json: (parsed, opts) =>
            validateAndTransformArray(parsed, (item, index) =>
                schema.json(item, {
                    ...opts,
                    breadcrumbsPrefix: [...(opts?.breadcrumbsPrefix ?? []), `[${index}]`],
                })
            ),
        getType: () => SchemaType.LIST,
    };

    return {
        ...maybeSkipValidation(baseSchema),
        ...getSchemaUtils(baseSchema),
    };
}

function validateAndTransformArray<Raw, Parsed>(
    value: unknown,
    transformItem: (item: Raw, index: number) => MaybeValid<Parsed>
): MaybeValid<Parsed[]> {
    if (!Array.isArray(value)) {
        return {
            ok: false,
            errors: [
                {
                    message: getErrorMessageForIncorrectType(value, "list"),
                    path: [],
                },
            ],
        };
    }

    const maybeValidItems = value.map((item, index) => transformItem(item, index));

    return maybeValidItems.reduce<MaybeValid<Parsed[]>>(
        (acc, item) => {
            if (acc.ok && item.ok) {
                return {
                    ok: true,
                    value: [...acc.value, item.value],
                };
            }

            const errors: ValidationError[] = [];
            if (!acc.ok) {
                errors.push(...acc.errors);
            }
            if (!item.ok) {
                errors.push(...item.errors);
            }

            return {
                ok: false,
                errors,
            };
        },
        { ok: true, value: [] }
    );
}

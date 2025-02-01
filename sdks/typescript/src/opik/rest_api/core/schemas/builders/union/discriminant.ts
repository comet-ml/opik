export function discriminant<RawDiscriminant extends string, ParsedDiscriminant extends string>(
    parsedDiscriminant: ParsedDiscriminant,
    rawDiscriminant: RawDiscriminant,
): Discriminant<RawDiscriminant, ParsedDiscriminant> {
    return {
        parsedDiscriminant,
        rawDiscriminant,
    };
}

export interface Discriminant<RawDiscriminant extends string, ParsedDiscriminant extends string> {
    parsedDiscriminant: ParsedDiscriminant;
    rawDiscriminant: RawDiscriminant;
}

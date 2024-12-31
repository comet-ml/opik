export function partition<T>(items: readonly T[], predicate: (item: T) => boolean): [T[], T[]] {
    const trueItems: T[] = [],
        falseItems: T[] = [];
    for (const item of items) {
        if (predicate(item)) {
            trueItems.push(item);
        } else {
            falseItems.push(item);
        }
    }
    return [trueItems, falseItems];
}

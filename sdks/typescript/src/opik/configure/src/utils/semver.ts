import { satisfies, subset, valid, validRange } from 'semver';

export function fulfillsVersionRange({
  version,
  acceptableVersions,
  canBeLatest,
}: {
  version: string;
  acceptableVersions: string;
  canBeLatest: boolean;
}): boolean {
  if (version === 'latest') {
    return canBeLatest;
  }

  let cleanedUserVersion, isRange;

  if (valid(version)) {
    cleanedUserVersion = valid(version);
    isRange = false;
  } else if (validRange(version)) {
    cleanedUserVersion = validRange(version);
    isRange = true;
  }

  return (
    // If the given version is a bogus format, this will still be undefined and we'll automatically reject it
    !!cleanedUserVersion &&
    (isRange
      ? subset(cleanedUserVersion, acceptableVersions)
      : satisfies(cleanedUserVersion, acceptableVersions))
  );
}

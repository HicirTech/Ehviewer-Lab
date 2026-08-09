/**
 * Version + update-feed invariants, kept as pure functions so they can be tested
 * without touching the repository. bump-version.ts and release.ts are thin CLIs
 * over this module.
 *
 * The rules encoded here are the ones that break users rather than the build:
 *   - versionCode must increase, or installed devices refuse the update
 *   - the feed must announce the same version the APK reports
 *   - fileDownloadUrl must point at *this* version's release, not a moving target
 */

export const REPO_RELEASES = "https://github.com/HicirTech/Ehviewer-Lab/releases";
export const RELEASE_TAG_BASE = `${REPO_RELEASES}/tag`;

/** Version scheme: "<upstream base>-hl.<N>", e.g. 2.0.2.3-hl.1 */
const NAME_PATTERN = /^(.*)-hl\.(\d+)$/;
const BASE_PATTERN = /^\d+(\.\d+)*$/;

export interface Version {
  name: string;
  code: number;
}

export function tagFor(versionName: string): string {
  return `v${versionName}`;
}

/** The release page for exactly this version. Never "/releases/latest": that drifts. */
export function releaseUrlFor(versionName: string): string {
  return `${RELEASE_TAG_BASE}/${tagFor(versionName)}`;
}

/**
 * Next version. Without a base, the fork iteration increments (hl.N -> hl.N+1);
 * with one, the base moves and the iteration restarts at hl.1 (used after an
 * upstream merge). versionCode always +1 either way.
 */
export function nextVersion(current: Version, base: string | null = null): Version {
  if (base !== null && !BASE_PATTERN.test(base)) {
    throw new Error(`--base "${base}" does not look like a version`);
  }
  const parsed = current.name.match(NAME_PATTERN);
  if (!parsed && base === null) {
    throw new Error(`versionName "${current.name}" is not <base>-hl.<N>; use --base to set one`);
  }
  const name = base !== null ? `${base}-hl.1` : `${parsed![1]}-hl.${Number(parsed![2]) + 1}`;
  return { name, code: current.code + 1 };
}

export function parseGradleVersion(gradle: string): Version {
  const code = gradle.match(/versionCode (\d+)/);
  const name = gradle.match(/versionName "([^"]+)"/);
  if (!code || !name) {
    throw new Error("versionCode/versionName not found in app/build.gradle");
  }
  return { name: name[1], code: Number(code[1]) };
}

export function applyGradleVersion(gradle: string, v: Version): string {
  return gradle
    .replace(/versionCode \d+/, `versionCode ${v.code}`)
    .replace(/versionName "[^"]+"/, `versionName "${v.name}"`);
}

/**
 * Returns a copy of the feed describing {@link Version}. Release notes are replaced
 * only when supplied, so a bump without notes keeps the previous lines as a
 * placeholder to edit by hand.
 */
export function syncFeed(feed: any, v: Version, notes: string[] = []): any {
  const next = structuredClone(feed);
  next.version = v.name;
  next.versionCode = v.code;
  next.updateContent = next.updateContent ?? {};
  next.updateContent.title = `EhViewer@Lab ${v.name}`;
  next.updateContent.fileDownloadUrl = releaseUrlFor(v.name);
  if (notes.length > 0) next.updateContent.content = notes;
  return next;
}

/**
 * Why the feed may not be released against this build, or null when consistent.
 * Guards the case where update.json was hand-edited and no longer matches the APK.
 */
export function feedInconsistency(feed: any, v: Version): string | null {
  if (feed?.version !== v.name) {
    return `update.json version "${feed?.version}" != build.gradle "${v.name}" - run bump-version first`;
  }
  if (feed?.versionCode !== v.code) {
    return `update.json versionCode ${feed?.versionCode} != build.gradle ${v.code}`;
  }
  const expected = releaseUrlFor(v.name);
  if (feed?.updateContent?.fileDownloadUrl !== expected) {
    return `update.json fileDownloadUrl "${feed?.updateContent?.fileDownloadUrl}" != "${expected}"`;
  }
  return null;
}

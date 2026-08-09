/**
 * Invariants for the release pipeline (#44).
 *
 * These guard failures that do not surface at release time:
 *   - a versionCode that stops increasing makes installed devices refuse the update
 *   - a feed that disagrees with the APK announces the wrong version, or nothing
 *   - a fileDownloadUrl that is not this version's tag sends users to the wrong build
 *
 * Most of the value is in the negative cases: the guards only matter when they refuse.
 */
import { describe, expect, test } from "bun:test";
import {
  applyGradleVersion,
  feedInconsistency,
  nextVersion,
  parseGradleVersion,
  releaseUrlFor,
  syncFeed,
  tagFor,
} from "./version.ts";

const GRADLE = `android {
    defaultConfig {
        applicationId "com.hicirtech.ehviewer"
        versionCode 2
        versionName "2.0.2.3-hl.2"
    }
}`;

const FEED = {
  version: "2.0.2.3-hl.2",
  versionCode: 2,
  mustUpdate: false,
  updateContent: {
    fileDownloadUrl: "https://github.com/HicirTech/Ehviewer-Lab/releases/tag/v2.0.2.3-hl.2",
    title: "EhViewer@Lab 2.0.2.3-hl.2",
    content: ["old note"],
  },
};

describe("parseGradleVersion", () => {
  test("reads name and code", () => {
    expect(parseGradleVersion(GRADLE)).toEqual({ name: "2.0.2.3-hl.2", code: 2 });
  });

  test("throws when the fields are missing rather than guessing", () => {
    expect(() => parseGradleVersion("android { }")).toThrow(/not found/);
  });
});

describe("nextVersion", () => {
  test("versionCode always increments by exactly one", () => {
    expect(nextVersion({ name: "2.0.2.3-hl.2", code: 2 }).code).toBe(3);
    expect(nextVersion({ name: "2.0.2.3-hl.2", code: 2 }, "2.0.3.0").code).toBe(3);
  });

  test("fork iteration increments and keeps the upstream base", () => {
    expect(nextVersion({ name: "2.0.2.3-hl.2", code: 2 }).name).toBe("2.0.2.3-hl.3");
    expect(nextVersion({ name: "2.0.2.3-hl.9", code: 9 }).name).toBe("2.0.2.3-hl.10");
  });

  test("a new base restarts the iteration at hl.1", () => {
    expect(nextVersion({ name: "2.0.2.3-hl.7", code: 7 }, "2.0.3.0").name).toBe("2.0.3.0-hl.1");
  });

  test("refuses a versionName it cannot parse unless a base is given", () => {
    expect(() => nextVersion({ name: "2.0.2.3", code: 1 })).toThrow(/is not <base>-hl/);
    expect(nextVersion({ name: "2.0.2.3", code: 1 }, "2.0.2.3").name).toBe("2.0.2.3-hl.1");
  });

  test("refuses a base that is not a version", () => {
    expect(() => nextVersion({ name: "2.0.2.3-hl.1", code: 1 }, "v2.0")).toThrow(/does not look like/);
    expect(() => nextVersion({ name: "2.0.2.3-hl.1", code: 1 }, "")).toThrow(/does not look like/);
  });
});

describe("applyGradleVersion", () => {
  test("rewrites both fields and leaves the rest alone", () => {
    const out = applyGradleVersion(GRADLE, { name: "2.0.3.0-hl.1", code: 3 });
    expect(out).toContain("versionCode 3");
    expect(out).toContain('versionName "2.0.3.0-hl.1"');
    expect(out).toContain('applicationId "com.hicirtech.ehviewer"');
  });
});

describe("release URL", () => {
  test("is this version's tag, never a moving target", () => {
    expect(tagFor("2.0.3.0-hl.1")).toBe("v2.0.3.0-hl.1");
    expect(releaseUrlFor("2.0.3.0-hl.1")).toBe(
      "https://github.com/HicirTech/Ehviewer-Lab/releases/tag/v2.0.3.0-hl.1",
    );
    expect(releaseUrlFor("2.0.3.0-hl.1")).not.toContain("/latest");
  });
});

describe("syncFeed", () => {
  const next = { name: "2.0.3.0-hl.1", code: 3 };

  test("syncs all four fields", () => {
    const out = syncFeed(FEED, next);
    expect(out.version).toBe("2.0.3.0-hl.1");
    expect(out.versionCode).toBe(3);
    expect(out.updateContent.title).toBe("EhViewer@Lab 2.0.3.0-hl.1");
    expect(out.updateContent.fileDownloadUrl).toBe(releaseUrlFor("2.0.3.0-hl.1"));
  });

  test("keeps previous notes when none are supplied, replaces them when they are", () => {
    expect(syncFeed(FEED, next).updateContent.content).toEqual(["old note"]);
    expect(syncFeed(FEED, next, ["a", "b"]).updateContent.content).toEqual(["a", "b"]);
  });

  test("does not mutate the input", () => {
    syncFeed(FEED, next, ["x"]);
    expect(FEED.version).toBe("2.0.2.3-hl.2");
    expect(FEED.updateContent.content).toEqual(["old note"]);
  });

  test("its output always satisfies the release gate", () => {
    expect(feedInconsistency(syncFeed(FEED, next), next)).toBeNull();
  });
});

describe("feedInconsistency", () => {
  const v = { name: "2.0.2.3-hl.2", code: 2 };

  test("accepts a feed that matches", () => {
    expect(feedInconsistency(FEED, v)).toBeNull();
  });

  test("refuses a stale version", () => {
    expect(feedInconsistency({ ...FEED, version: "2.0.2.3-hl.1" }, v)).toMatch(/version/);
  });

  test("refuses a versionCode that disagrees with the APK", () => {
    expect(feedInconsistency({ ...FEED, versionCode: 1 }, v)).toMatch(/versionCode/);
  });

  test("refuses a fileDownloadUrl pointing anywhere but this version's tag", () => {
    const latest = {
      ...FEED,
      updateContent: {
        ...FEED.updateContent,
        fileDownloadUrl: "https://github.com/HicirTech/Ehviewer-Lab/releases/latest",
      },
    };
    expect(feedInconsistency(latest, v)).toMatch(/fileDownloadUrl/);

    const otherVersion = {
      ...FEED,
      updateContent: { ...FEED.updateContent, fileDownloadUrl: releaseUrlFor("9.9.9-hl.1") },
    };
    expect(feedInconsistency(otherVersion, v)).toMatch(/fileDownloadUrl/);
  });

  test("refuses a feed missing updateContent instead of throwing", () => {
    expect(feedInconsistency({ version: v.name, versionCode: v.code }, v)).toMatch(/fileDownloadUrl/);
  });
});

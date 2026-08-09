#!/usr/bin/env bun
/**
 * Cut a release: verify everything is consistent, then push the tag that
 * triggers .github/workflows/release.yml (signed APK on a GitHub release).
 *
 * Checks before touching anything:
 *   - working tree clean, on main, in sync with origin/main
 *   - feedauthor/update.json version/versionCode match app/build.gradle
 *     (otherwise installed apps would never see - or wrongly see - an update)
 *   - tag v<versionName> does not exist locally or on origin
 *
 * Usage:  bun script/release.ts [--dry-run]
 */
import { readFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { feedInconsistency, parseGradleVersion, tagFor } from "./version.ts";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const dryRun = process.argv.includes("--dry-run");

function git(...argv: string[]): string {
  const r = Bun.spawnSync(["git", ...argv], { cwd: root });
  if (r.exitCode !== 0) {
    throw new Error(`git ${argv.join(" ")} failed:\n${r.stderr.toString()}`);
  }
  return r.stdout.toString().trim();
}

function fail(msg: string): never {
  console.error(`error: ${msg}`);
  process.exit(1);
}

// --- consistency: build.gradle vs update feed ---
// The rules live in ./version.ts so they can be tested without a repository; see #44.
// The update dialog opens fileDownloadUrl directly, so a stale one sends users to the
// wrong release. bump-version writes it; this catches a hand-edited feed.
let version;
try {
  version = parseGradleVersion(readFileSync(resolve(root, "app/build.gradle"), "utf8"));
} catch (e) {
  fail((e as Error).message);
}
const versionName = version.name;
const versionCode = version.code;
const feed = JSON.parse(readFileSync(resolve(root, "feedauthor/update.json"), "utf8"));
const inconsistency = feedInconsistency(feed, version);
if (inconsistency) fail(inconsistency);

// --- git state ---
if (git("status", "--porcelain") !== "") fail("working tree not clean - commit or stash first");
const branch = git("rev-parse", "--abbrev-ref", "HEAD");
if (branch !== "main") fail(`on branch "${branch}" - releases are cut from main`);
git("fetch", "origin", "main", "--tags");
if (git("rev-parse", "HEAD") !== git("rev-parse", "origin/main"))
  fail("local main is not in sync with origin/main - pull/push first");

const tag = tagFor(versionName);
const existing = Bun.spawnSync(["git", "rev-parse", "--verify", "--quiet", `refs/tags/${tag}`], { cwd: root });
if (existing.exitCode === 0) fail(`tag ${tag} already exists`);

console.log(`release ${tag}  (versionCode ${versionCode}, feed in sync, main clean & synced)`);
if (dryRun) {
  console.log("dry run: tag not created");
  process.exit(0);
}

git("tag", "-a", tag, "-m", `EhViewer@Lab ${versionName}`);
git("push", "origin", tag);
console.log(`pushed ${tag} - the Release workflow is building the signed APK.`);
console.log(`watch:   gh run watch --repo HicirTech/Ehviewer-Lab`);
console.log(`release: https://github.com/HicirTech/Ehviewer-Lab/releases/tag/${tag}`);

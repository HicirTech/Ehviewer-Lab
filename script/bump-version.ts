#!/usr/bin/env bun
/**
 * Bump the app version and keep the update feed in sync.
 *
 * The version scheme is "<upstream base>-hl.<N>" (e.g. 2.0.2.3-hl.1):
 *   default            -> hl.N + 1                (fork iteration)
 *   --base <version>   -> new upstream base, hl.1 (after an upstream merge)
 * versionCode always increments by 1 (it must stay monotonic or installed
 * devices will refuse the update).
 *
 * Edits app/build.gradle and feedauthor/update.json. Pass one --notes per
 * release-note line for update.json; without --notes the previous content is
 * kept as a placeholder for manual editing.
 *
 * Usage:
 *   bun script/bump-version.ts [--base 2.0.3.0] [--notes "line"]... [--dry-run]
 */
import { readFileSync, writeFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const gradlePath = resolve(root, "app/build.gradle");
const feedPath = resolve(root, "feedauthor/update.json");

const args = process.argv.slice(2);
const dryRun = args.includes("--dry-run");
let base: string | null = null;
const notes: string[] = [];
for (let i = 0; i < args.length; i++) {
  if (args[i] === "--base") base = args[++i] ?? fail("--base needs a value");
  else if (args[i] === "--notes") notes.push(args[++i] ?? fail("--notes needs a value"));
  else if (args[i] !== "--dry-run") fail(`unknown argument: ${args[i]}`);
}

function fail(msg: string): never {
  console.error(`error: ${msg}`);
  process.exit(1);
}

let gradle = readFileSync(gradlePath, "utf8");

const codeMatch = gradle.match(/versionCode (\d+)/);
const nameMatch = gradle.match(/versionName "([^"]+)"/);
if (!codeMatch || !nameMatch) fail("versionCode/versionName not found in app/build.gradle");

const oldCode = Number(codeMatch[1]);
const oldName = nameMatch[1];
const parsed = oldName.match(/^(.*)-hl\.(\d+)$/);
if (!parsed && !base) fail(`versionName "${oldName}" is not <base>-hl.<N>; use --base to set one`);

const newCode = oldCode + 1;
const newName = base ? `${base}-hl.1` : `${parsed![1]}-hl.${Number(parsed![2]) + 1}`;
if (base && !/^\d+(\.\d+)*$/.test(base)) fail(`--base "${base}" does not look like a version`);

gradle = gradle
  .replace(/versionCode \d+/, `versionCode ${newCode}`)
  .replace(/versionName "[^"]+"/, `versionName "${newName}"`);

const feed = JSON.parse(readFileSync(feedPath, "utf8"));
feed.version = newName;
feed.versionCode = newCode;
feed.updateContent.title = `EhViewer@Lab ${newName}`;
if (notes.length > 0) feed.updateContent.content = notes;

console.log(`versionName  ${oldName}  ->  ${newName}`);
console.log(`versionCode  ${oldCode}  ->  ${newCode}`);
console.log(`update feed  version/versionCode/title synced${notes.length ? `, ${notes.length} note line(s)` : " (content kept, edit manually if needed)"}`);

if (dryRun) {
  console.log("dry run: nothing written");
} else {
  writeFileSync(gradlePath, gradle);
  writeFileSync(feedPath, JSON.stringify(feed, null, 2) + "\n");
  console.log("written. next: review, commit, then run: bun script/release.ts");
}

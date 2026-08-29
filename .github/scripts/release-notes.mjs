import { execFile } from "node:child_process";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { promisify } from "node:util";
import { generateNotes as generateConventionalNotes } from "@semantic-release/release-notes-generator";

const execFileAsync = promisify(execFile);
const RELEASE_HEADING = /^# v(\d+\.\d+\.\d+(?:-[^\s]+)?)(?:\s+\([^\n]+\))?\s*$/gm;

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function extractMigrationSection(content, heading) {
  const normalized = content.replace(/\r\n/g, "\n");
  const headingPattern = new RegExp(`^${escapeRegExp(heading)}\\s*$`, "m");
  const match = headingPattern.exec(normalized);

  if (!match) {
    throw new Error(`Migration changelog heading not found: ${heading}`);
  }

  const start = match.index;
  const afterHeading = start + match[0].length;
  const remainder = normalized.slice(afterHeading);
  const nextRelease = /^# v\d+\.\d+\.\d+(?:-[^\s]+)?(?:\s+\([^\n]+\))?\s*$/m.exec(remainder);
  const end = nextRelease ? afterHeading + nextRelease.index : normalized.length;
  const section = normalized.slice(start, end).trim();
  if (!section.replace(heading, "").trim()) {
    throw new Error(`Migration changelog section is empty: ${heading}`);
  }

  return section;
}

function extractStableMigrationSections(content, includeVersions) {
  const normalized = content.replace(/\r\n/g, "\n");
  const matches = [...normalized.matchAll(RELEASE_HEADING)];
  const sections = [];

  for (let index = 0; index < matches.length; index += 1) {
    const match = matches[index];
    const version = match[1];
    if (!includeVersions.some((pattern) => new RegExp(pattern).test(version))) {
      continue;
    }

    const start = match.index;
    const end = index + 1 < matches.length ? matches[index + 1].index : normalized.length;
    sections.push(normalized.slice(start, end).trim());
  }

  if (sections.length === 0) {
    throw new Error("No historical development changelog sections matched the stable migration configuration.");
  }

  return sections;
}
function omitHistoricalBuildTypes(section) {
  return section.replace(/\n+## Build types\s*[\s\S]*$/i, "").trim();
}

function demoteHistoricalSection(section) {
  return omitHistoricalBuildTypes(section)
    .split("\n")
    .map((line) => {
      if (/^# v\d+\.\d+\.\d+/.test(line)) {
        return `### ${line.slice(2)}`;
      }
      if (/^# /.test(line)) {
        return `#### ${line.slice(2)}`;
      }
      return line;
    })
    .join("\n");
}

function stripGeneratedReleaseHeading(notes) {
  const normalized = notes.trim();
  return normalized.replace(/^# .*?(?:\n+|$)/, "").trim();
}

async function commitsSinceTag(context, tag) {
  const options = { cwd: context.cwd, windowsHide: true };

  try {
    await execFileAsync("git", ["rev-parse", "--verify", `refs/tags/${tag}`], options);
    await execFileAsync("git", ["merge-base", "--is-ancestor", tag, "HEAD"], options);
  } catch {
    throw new Error(
      `Stable migration requires ${tag} to exist and be an ancestor of main. Publish the first dev prerelease before merging dev into main.`,
    );
  }

  const { stdout } = await execFileAsync("git", ["rev-list", `${tag}..HEAD`], options);
  const hashes = new Set(stdout.split(/\r?\n/).filter(Boolean));
  return context.commits.filter((commit) => hashes.has(commit.hash));
}
async function generateStableMigrationNotes(pluginConfig, context, migration) {
  const changelogPath = resolve(context.cwd, migration.changelogFile);
  const content = await readFile(changelogPath, "utf8");
  const historicalSections = extractStableMigrationSections(content, migration.includeVersions)
    .map(demoteHistoricalSection)
    .join("\n\n");

  const recentCommits = await commitsSinceTag(context, migration.automaticSinceTag);
  let automaticNotes = "";

  if (recentCommits.length > 0) {
    const generated = await generateConventionalNotes(pluginConfig, {
      ...context,
      commits: recentCommits,
    });
    automaticNotes = stripGeneratedReleaseHeading(generated);
  }

  context.logger.log(
    "Using one-time stable migration notes from %s plus %d commits after %s",
    migration.changelogFile,
    recentCommits.length,
    migration.automaticSinceTag,
  );

  const parts = [
    `# v${context.nextRelease.version}`,
    "## Development release history",
    historicalSections,
  ];

  if (automaticNotes) {
    parts.push(`## Changes after ${migration.automaticSinceTag}`, automaticNotes);
  }

  return parts.join("\n\n");
}
export async function generateNotes(pluginConfig, context) {
  const { migration, stableMigration, ...releaseNotesConfig } = pluginConfig;
  const branchName = context.branch?.name;
  const version = context.nextRelease?.version;

  if (migration && branchName === migration.branch && version === migration.version) {
    const changelogPath = resolve(context.cwd, migration.changelogFile);
    const content = await readFile(changelogPath, "utf8");
    const notes = extractMigrationSection(content, migration.heading);

    context.logger.log(
      "Using migration release notes from %s for %s on %s",
      migration.changelogFile,
      version,
      branchName,
    );

    return notes;
  }

  if (stableMigration && branchName === stableMigration.branch && version === stableMigration.version) {
    return generateStableMigrationNotes(releaseNotesConfig, context, stableMigration);
  }

  return generateConventionalNotes(releaseNotesConfig, context);
}
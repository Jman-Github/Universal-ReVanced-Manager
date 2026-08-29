import { appendFile, readFile } from "node:fs/promises";
import semanticRelease from "semantic-release";

function bumpPatch(version) {
  const match = /^(\d+)\.(\d+)\.(\d+)$/.exec(version);
  if (!match) {
    throw new Error(`Unable to bump non-stable version: ${version}`);
  }
  return `${match[1]}.${match[2]}.${Number(match[3]) + 1}`;
}

async function currentProjectVersion() {
  const properties = await readFile("gradle.properties", "utf8");
  const match = /^\s*version\s*=\s*(\S+)\s*$/m.exec(properties);
  if (!match) {
    throw new Error("Unable to resolve version from gradle.properties");
  }
  return match[1];
}

const release = await semanticRelease(
  { dryRun: true, noCi: true },
  { cwd: process.cwd(), env: process.env },
);
const currentVersion = await currentProjectVersion();
let prospectiveVersion = release?.nextRelease?.version;

if (!prospectiveVersion) {
  prospectiveVersion = currentVersion.includes("-")
    ? currentVersion
    : bumpPatch(currentVersion);
}

const debugVersion = prospectiveVersion.includes("-")
  ? `${prospectiveVersion}-debug`
  : `${prospectiveVersion}-dev-debug`;

console.log(`Current project version: ${currentVersion}`);
console.log(`Prospective release version: ${prospectiveVersion}`);
console.log(`Debug version: ${debugVersion}`);

if (process.env.GITHUB_OUTPUT) {
  await appendFile(process.env.GITHUB_OUTPUT, `version=${debugVersion}\n`, "utf8");
} else {
  console.log(`version=${debugVersion}`);
}

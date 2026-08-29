import { execFile } from "node:child_process";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

async function git(args, { cwd, env }) {
  return execFileAsync("git", args, { cwd, env });
}

async function createStatus(sha, context) {
  const { env } = context;
  const token = env.GITHUB_TOKEN;
  const repository = env.GITHUB_REPOSITORY;
  const apiUrl = env.GITHUB_API_URL ?? "https://api.github.com";

  if (!token || !repository) {
    throw new Error("GITHUB_TOKEN and GITHUB_REPOSITORY are required to certify a main release commit");
  }

  const targetUrl = env.GITHUB_SERVER_URL && env.GITHUB_RUN_ID
    ? `${env.GITHUB_SERVER_URL}/${repository}/actions/runs/${env.GITHUB_RUN_ID}`
    : undefined;

  const response = await fetch(`${apiUrl}/repos/${repository}/statuses/${sha}`, {
    method: "POST",
    headers: {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${token}`,
      "X-GitHub-Api-Version": "2022-11-28",
    },
    body: JSON.stringify({
      state: "success",
      context: "Crowdin download completed",
      description: "Release metadata commit from verified stable promotion",
      ...(targetUrl ? { target_url: targetUrl } : {}),
    }),
  });

  if (!response.ok) {
    throw new Error(`Failed to certify release commit ${sha}: ${response.status} ${await response.text()}`);
  }
}

export async function prepare(pluginConfig, context) {
  const { cwd, env, branch, nextRelease, logger, options } = context;
  const assets = pluginConfig.assets ?? [];
  if (assets.length === 0) return;

  await git(["add", "--force", "--ignore-errors", ...assets], { cwd, env });
  const { stdout: staged } = await git(["diff", "--cached", "--name-only", "--", ...assets], { cwd, env });
  if (!staged.trim()) {
    logger.log("No generated release files changed");
    return;
  }

  let message = `chore: release v${nextRelease.version} [skip ci]\n\n${nextRelease.notes}`;
  if (branch.name === "main") {
    const verifiedDevSha = env.CROWDIN_VERIFIED_DEV_SHA;
    if (!/^[0-9a-f]{40}$/i.test(verifiedDevSha ?? "")) {
      throw new Error("CROWDIN_VERIFIED_DEV_SHA must contain the verified 40-character dev commit SHA for a stable release");
    }
    message += `\n\nCrowdin-Dev-Head: ${verifiedDevSha}`;
  }

  const tempDir = await mkdtemp(join(tmpdir(), "urv-release-git-"));
  const messageFile = join(tempDir, "message.txt");

  try {
    await writeFile(messageFile, message, "utf8");
    await git(["commit", "-F", messageFile], { cwd, env });
    const { stdout } = await git(["rev-parse", "HEAD"], { cwd, env });
    const releaseSha = stdout.trim();
    const repositoryUrl = options.repositoryUrl;

    if (branch.name !== "main") {
      await git(["push", repositoryUrl, `HEAD:${branch.name}`], { cwd, env });
      logger.log("Prepared Git release: %s", nextRelease.gitTag);
      return;
    }

    const stagingBranch = `semantic-release-status/${nextRelease.version}-${releaseSha.slice(0, 12)}`;
    const stagingRef = `refs/heads/${stagingBranch}`;

    await git(["push", repositoryUrl, `HEAD:${stagingRef}`], { cwd, env });
    try {
      await createStatus(releaseSha, context);
      await git(["push", repositoryUrl, "HEAD:main"], { cwd, env });
    } finally {
      await git(["push", repositoryUrl, `:${stagingRef}`], { cwd, env }).catch(() => {});
    }

    logger.log("Prepared and certified Git release: %s", nextRelease.gitTag);
  } finally {
    await rm(tempDir, { recursive: true, force: true });
  }
}
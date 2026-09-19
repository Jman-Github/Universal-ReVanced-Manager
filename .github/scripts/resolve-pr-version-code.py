"""Read the newest published release/prerelease APK's actual Android version code."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def latest_release(pages):
    releases = [
        release for page in pages for release in page
        if not release.get("draft") and release.get("published_at")
    ]
    if not releases:
        raise ValueError("No published release or prerelease is available")
    return max(releases, key=lambda release: release["published_at"])


def manager_asset(release):
    assets = [
        asset for asset in release.get("assets", [])
        if asset["name"].startswith("universal-revanced-manager-")
        and asset["name"].endswith(".apk")
    ]
    if not assets:
        raise ValueError("The newest release has no manager APK; refusing a guessed version code")
    return min(assets, key=lambda asset: (not asset["name"].endswith("-universal.apk"), asset["name"]))


def version_code(badging):
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)'", badging, re.MULTILINE)
    if package is None or package[1] != "app.universal.revanced.manager":
        raise ValueError("The release APK has an unexpected package identity")
    code = int(package[2])
    if not 1 <= code <= 2_100_000_000:
        raise ValueError("The release APK's version code is outside Android's supported range")
    return code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--aapt2", required=True)
    args = parser.parse_args()
    pages = json.loads(subprocess.check_output([
        "gh", "api", "--paginate", "--slurp",
        f"repos/{args.repository}/releases?per_page=100",
    ], text=True, encoding="utf-8"))
    release = latest_release(pages)
    asset = manager_asset(release)
    with tempfile.TemporaryDirectory(prefix="urv-pr-release-") as temp:
        subprocess.run([
            "gh", "release", "download", release["tag_name"],
            "--repo", args.repository, "--pattern", asset["name"], "--dir", temp,
        ], check=True)
        apk = Path(temp) / asset["name"]
        code = version_code(subprocess.check_output([
            args.aapt2, "dump", "badging", str(apk),
        ], text=True, encoding="utf-8"))
    print(f"PR versionCode={code}, from {release['tag_name']} ({asset['name']})")
    if os.environ.get("GITHUB_ENV"):
        with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as output:
            output.write(f"ORG_GRADLE_PROJECT_prReleaseVersionCode={code}\n")


if __name__ == "__main__":
    main()

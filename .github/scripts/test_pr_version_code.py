import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("resolver", Path(__file__).with_name("resolve-pr-version-code.py"))
resolver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(resolver)


class ReleaseVersionCodeTest(unittest.TestCase):
    def test_newest_publication_can_be_stable_or_prerelease(self):
        for prerelease in (False, True):
            newest = {"tag_name": "new", "published_at": "2026-09-13T00:00:00Z", "prerelease": prerelease}
            pages = [[{"published_at": "2026-09-12T00:00:00Z"}],
                     [newest, {"draft": True, "published_at": "2026-09-14T00:00:00Z"}]]
            self.assertEqual(resolver.latest_release(pages), newest)

    def test_missing_newest_manager_apk_fails(self):
        with self.assertRaises(ValueError):
            resolver.manager_asset({"assets": [{"name": "revanced.v21-plugin.apk"}]})

    def test_universal_manager_is_preferred(self):
        assets = [{"name": "revanced.v21-plugin.apk"},
                  {"name": "universal-revanced-manager-v1-arm64-v8a.apk"},
                  {"name": "universal-revanced-manager-v1-universal.apk"}]
        self.assertEqual(resolver.manager_asset({"assets": assets}), assets[2])

    def test_legacy_code_is_read_without_recalculating_it(self):
        self.assertEqual(resolver.version_code(
            "package: name='app.universal.revanced.manager' versionCode='10080100' versionName='1.8.1-dev.22'"
        ), 10080100)

    def test_other_packages_and_invalid_codes_fail(self):
        for package, code in [("plugin", 10080100), ("app.universal.revanced.manager", 0)]:
            with self.assertRaises(ValueError):
                resolver.version_code(f"package: name='{package}' versionCode='{code}'")


if __name__ == "__main__":
    unittest.main()

"""Check empty-store/reset contracts and removed default-bundle setup before assembleRelease."""
from pathlib import Path
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[4]
MAIN = ROOT / "app/src/main/java/app/universal/patch/manager"
dao = (MAIN / "data/room/bundles/PatchBundleDao.kt").read_text(encoding="utf-8")
reset_sql = re.search(r'@Query\("([^"]+)"\)\s+suspend fun reset\(\)', dao)[1]
with sqlite3.connect(":memory:") as db:
    db.execute("CREATE TABLE patch_bundles (uid INTEGER PRIMARY KEY)")
    db.executemany("INSERT INTO patch_bundles VALUES (?)", [(0,), (1,), (42,)])
    db.execute(reset_sql)
    assert db.execute("SELECT COUNT(*) FROM patch_bundles").fetchone()[0] == 0
repository = (MAIN / "domain/repository/PatchBundleRepository.kt").read_text(encoding="utf-8")
load = repository.split("private suspend fun loadFromDb()", 1)[1].split("fun discoveryImportKey", 1)[0]
assert "dao.all().mapNotNull" in load
assert "if (!isLegacyOfficialBundle(entity.uid, entity.source.toString())) return@mapNotNull entity" in load
assert "source = SourceInfo.Remote" in load
assert "prefs.legacyOfficialBundleRemoved.get()" in load and "dao.remove(entity.uid)" in load
assert "legacyOfficialBundleRegistryUrl" in load
assert "uid =" not in load, "Migration must preserve the old source UID and selections"
for removed in ("defaultSource()", "restoreDefaultBundle", "refreshDefaultBundle",
                "enforceOfficialOrderPreference", "APIPatchBundle"):
    assert removed not in repository, removed
settings = (MAIN / "ui/viewmodel/ImportExportViewModel.kt").read_text(encoding="utf-8")
refresh = settings.split("private suspend fun refreshImportedRepositories", 1)[1].split(
    "private suspend fun buildImportedPermissionRequest", 1)[0]
assert "createRemote" not in refresh and "restore" not in refresh.lower()
assert "officialBundle" not in refresh
export = settings.split("private suspend fun buildPatchBundleExportResult", 1)[1].split(
    "fun exportPatchBundles", 1)[0]
assert "SourceInfo.API" not in export and "officialState" not in export
print("PASS: reset deletes every source including UID 0; empty stores are not seeded")
print("PASS: existing legacy sources retain their UID; settings imports and exports create no synthetic bundle")

package app.urv.manager.network.api

import app.urv.manager.network.dto.*
import app.urv.manager.domain.bundles.*
import app.urv.manager.network.utils.*
import app.urv.manager.ui.component.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.*
import java.time.Duration

private val json = Json { ignoreUnknownKeys = true }
private fun bundle(i: Int) = buildJsonObject {
    put("catalog_id", "b$i"); put("id", i)
    put("is_latest", i % 4 == 0); put("is_prerelease", i >= 40)
    put("bundle_type", if (i % 2 == 0) "ReVanced:v21" else "Reseam")
    put("version", "v$i"); put("metadata_status", "verified")
    put("need_patches_update", false); put("patcher_failure_fingerprint", JsonNull)
    put("source", buildJsonObject { put("url", "source${i % 2}") })
}

private fun matches(row: JsonObject, where: JsonObject): Boolean = where.all { (key, expression) ->
    when (key) {
        "_and" -> expression.jsonArray.all { matches(row, it.jsonObject) }
        "_or" -> expression.jsonArray.any { matches(row, it.jsonObject) }
        else -> expression.jsonObject.all { (operator, value) ->
            val actual = row[key] ?: JsonNull
            when (operator) {
                "_eq" -> actual == value
                "_in" -> actual in value.jsonArray
                "_is_null" -> (actual == JsonNull) == value.jsonPrimitive.boolean
                else -> matches(actual.jsonObject, expression.jsonObject)
            }
        }
    }
}

private class Fake {
    var generation = "g1"
    var switchGeneration = false
    var qualifications = 0
    var calls = 0
    var forceCalls = 0
    var apps = (0 until 80).map { i ->
        CatalogApp("com.example.app%03d".format(i), "Application $i", "https://icons/$i",
            listOf(CatalogAppBundle("b$i", listOf("1.0"), i % 5 == 0)))
    }
    val api = CatalogAppsIndex { query, variables, force, _, budget ->
        check(--budget.remaining >= 0)
        check(query.toByteArray().size + variables.toString().toByteArray().size < 32 * 1024)
        check("patch_packages" !in query && "patch_packages" !in variables.toString())
        calls++
        if (force) forceCalls++
        val result = buildJsonObject {
            if (query.contains("AppBundleMatches")) {
                qualifications++
                if (switchGeneration) { generation = "g2"; switchGeneration = false }
                val scope = variables.getValue("scope").jsonObject
                val groups = variables.filterKeys { it.startsWith("ids") }
                check(groups.size <= 4)
                groups.forEach { (key, ids) ->
                    check(ids.jsonArray.size <= 70)
                    val found = ids.jsonArray.map { it.jsonPrimitive.content.removePrefix("b").toInt() }
                        .filter { matches(bundle(it), scope) }
                    put("m" + key.removePrefix("ids"), buildJsonArray {
                        found.forEach { add(buildJsonObject { put("catalog_id", "b$it") }) }
                    })
                }
            } else if (query.contains("AppScopeBundles")) {
                val offset = variables.getValue("offset").jsonPrimitive.int
                put("bundle", JsonArray((0 until 80).map(::bundle)
                    .filter { matches(it, variables.getValue("where").jsonObject) }.drop(offset).take(100)))
            } else {
                val expected = variables["generation"]
                check(expected == null || expected == JsonNull || expected.jsonPrimitive.content == generation) {
                    "Catalog changed; restart history pagination"
                }
                val offset = variables.getValue("offset").jsonPrimitive.int
                put("historical_apps", json.encodeToJsonElement(apps.drop(offset).take(variables.getValue("limit").jsonPrimitive.int)))
            }
            put("catalog_generation", generation)
        }
        APIResponse.Success(result)
    }
}

private suspend fun contracts() {
    val fake = Fake()
    suspend fun page(filters: CatalogFilters, offset: Int = 0) =
        fake.api.getApps(filters, offset, false).getOrThrow()
    check(page(CatalogFilters()).size == 20)
    val before = fake.qualifications
    check(page(CatalogFilters()).size == 20 && fake.qualifications == before)
    for (release in listOf("release", "prerelease")) {
        val first = page(CatalogFilters(release = release))
        val second = page(CatalogFilters(release = release), 30)
        check(first.size == 30 && second.size == 10)
        check((first + second).map { it.name }.distinct().size == 40)
        check(first.all { it.appName != null && it.iconUrl != null })
    }
    check(page(CatalogFilters(release = "all", sourceUrl = "source1")).size == 30)
    check(page(CatalogFilters(release = "all", sourceUrl = "source1"), 30).size == 10)
    check(page(CatalogFilters(release = "all", bundleType = "ReVanced:v21")).size == 30)
    check(page(CatalogFilters(release = "all", version = "v17")).single().name == "com.example.app017")
    check(page(CatalogFilters(release = "all", query = "Application 17")).single().name == "com.example.app017")
    check(page(CatalogFilters(release = "all", packageName = "com.example.app017", exactPackage = true)).size == 1)
    check(page(CatalogFilters(release = "all", packageName = "com.example.app017@2", exactPackage = true)).isEmpty())
    check(page(CatalogFilters(release = "all", packageName = "com.example.app015@2", exactPackage = true)).size == 1)
    val checks = fake.qualifications
    check(page(CatalogFilters(release = "all", sourceUrl = "missing", query = "does-not-exist")).isEmpty())
    check(fake.qualifications == checks)

    val chunked = Fake()
    chunked.apps = listOf(CatalogApp("chunked", bundles = (1..150).map {
        CatalogAppBundle("b${if (it == 150) 4 else it * 4 + 41}")
    }))
    check(chunked.api.getApps(CatalogFilters(release = "release"), 0, false).getOrThrow().size == 1)
    check(chunked.qualifications == 1)
    val changed = Fake().apply { switchGeneration = true }
    check(changed.api.getApps(CatalogFilters(release = "release"), 0, false).getOrThrow().size == 30)
    check(changed.forceCalls > 0)
    changed.generation = "g3"
    check(runCatching {
        changed.api.getApps(CatalogFilters(release = "release"), 30, false).getOrThrow()
    }.exceptionOrNull()?.message.orEmpty().contains("Catalog changed"))
    val cancel = CatalogAppsIndex { _, _, _, _, _ -> throw CancellationException("cancel") }
    check(runCatching { cancel.getApps(CatalogFilters(), 0, false) }.exceptionOrNull() is CancellationException)
    val ids = fake.api.matchingBundles(CatalogFilters(packageName = "com.example.app017", exactPackage = true),
        listOf(BundleNode(17, catalogId = "b17"), BundleNode(18, catalogId = "b18")),
        fake.generation, false)
    check(ids == setOf(17))
    val count = fake.calls
    check(fake.api.matchingBundles(CatalogFilters(), emptyList(), null, false).isEmpty())
    check(fake.calls == count)
    val many = Fake().apply {
        apps = (0 until 250).map { CatalogApp("pkg%03d".format(it),
            bundles = listOf(CatalogAppBundle("b0"))) }
    }
    val middle = many.api.getApps(CatalogFilters(), 90, false).getOrThrow()
    check(middle.first().name == "pkg090" && middle.last().name == "pkg119")
    check(many.api.getApps(CatalogFilters(), 240, false).getOrThrow().size == 10)
    println("App index contracts passed: channels, pagination, names/icons, combined filters, caching, ID chunks, generations, cancellation.")
}

private fun geometryContracts() {
    val measurements = LazyScrollbarMeasurements()
    check(measurements.sizes(6, listOf(LazyScrollbarSample(0, "header", 1000)), 200) ==
        listOf(1000, 200, 200, 200, 200, 200))
    val sizes = measurements.sizes(6, listOf(LazyScrollbarSample(0, "header", 1000),
        LazyScrollbarSample(1, "a", 200), LazyScrollbarSample(2, "b", 200)), 100)
    check(sizes == listOf(1000, 200, 200, 200, 200, 200))
    fun geometry(index: Int, offset: Int, back: Boolean = true, forward: Boolean = true, reverse: Boolean = false) =
        lazyScrollbarGeometry(sizes, index, offset, 10, 20, 30, 500, back, forward, reverse)
    val middle = geometry(1, 40)
    check(middle.contentSize == 2100.0 && middle.offset == 1050.0)
    check(middle.target(middle.progress) == 1 to 40)
    check(geometry(0, 0, back = false).progress == 0f)
    check(geometry(5, 200, forward = false).progress == 1f)
    check(geometry(0, 0, back = false, reverse = true).progress == 1f)
    check(middle.target(1f) == 5 to 230)
    check(middle.thumbSize(500f, 24f) in 119f..120f)
    check(lazyScrollbarGeometry(emptyList(), 0, 0, 0, 0, 0, 500, false, false, false).range == 0.0)
    val replaced = measurements.sizes(6, listOf(LazyScrollbarSample(1, "replacement", 100)), 100)
    check(replaced.all { it == 100 })
    println("Scrollbar geometry contracts passed: mixed heights, padding, spacing, endpoints, dragging, reverse layout.")
}

private suspend fun live(scopedOnly: Boolean) {
    val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
    val cache = mutableMapOf<String, JsonObject>()
    var requests = 0
    val api = CatalogAppsIndex { query, variables, force, identity, budget ->
        val key = "$query|$variables|$identity"
        val cached = cache[key].takeUnless { force }
        val data = cached ?: run {
            check(--budget.remaining >= 0) { "request budget exhausted" }
            val payload = buildJsonObject { put("query", query); put("variables", variables) }.toString()
            val request = HttpRequest.newBuilder(URI("https://patch-bundles.api.jmancentral.com/graphql"))
                .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            requests++
            check(response.statusCode() == 200) { "HTTP ${response.statusCode()}: ${response.body()}" }
            val parsed = json.parseToJsonElement(response.body()).jsonObject
            check(parsed["errors"] == null) { parsed["errors"].toString() }
            parsed.getValue("data").jsonObject.also { cache[key] = it }
        }
        APIResponse.Success(data)
    }
    for (release in if (scopedOnly) listOf("latest") else listOf("latest", "release", "prerelease")) {
        val filters = CatalogFilters(release = release)
        val start = System.nanoTime()
        val first = api.getApps(filters, 0, false).getOrThrow()
        val second = api.getApps(filters, first.size, false).getOrThrow()
        check(first.isNotEmpty())
        check((first + second).map { it.name }.distinct().size == first.size + second.size)
        println("LIVE $release: ${first.size}+${second.size} apps, ${requests} total network requests, ${(System.nanoTime()-start)/1_000_000}ms")
    }
    for (filters in listOf(
        CatalogFilters(release = if (scopedOnly) "latest" else "all", bundleType = "ReVanced:v21"),
        CatalogFilters(release = "all", sourceUrl = "https://github.com/hajs110208/morphe-patches"),
        CatalogFilters(release = "latest", packageName = "youtube"),
        CatalogFilters(release = "release", query = "YouTube"),
    )) {
        val rows = api.getApps(filters, 0, false).getOrThrow()
        println("LIVE filter ${filters}: ${rows.size} apps, ${requests} total network requests")
    }
    val ids = cache.values.flatMap { data -> data["bundle"]?.jsonArray.orEmpty() }
        .map { it.jsonObject.getValue("catalog_id").jsonPrimitive.content }.distinct()
    val candidates = ids.mapIndexed { index, id -> BundleNode(index, catalogId = id) }
    val generation = cache.values.first().getValue("catalog_generation").jsonPrimitive.content
    val supported = api.matchingBundles(CatalogFilters(packageName = "com.google.android.youtube",
        exactPackage = true), candidates, generation, false)
    check(supported.isNotEmpty())
    println("LIVE Bundles exact-package search: ${supported.size} matching bundles; $requests total requests")
}

private fun releaseUrlContracts() {
    check(releasePageFromArtifact("https://github.com/a/b/releases/download/v1.2/bundle.mpp") ==
        "https://github.com/a/b/releases/tag/v1.2")
    check(releasePageFromArtifact("https://github.com/a/b/releases/tag/v1%2Fbeta?x=1") ==
        "https://github.com/a/b/releases/tag/v1%2Fbeta")
    check(releasePageFromArtifact("https://github.com/releases/releases/releases/download/latest/b.rvp") ==
        "https://github.com/releases/releases/releases/tag/latest")
    check(releasePageFromArtifact("https://github.com/a/b/releases/latest/download/bundle.rvp") == null)
    check(releasePageFromArtifact("https://gitlab.com/group/nested/repo/-/releases/tag/downloads/a.mpp") ==
        "https://gitlab.com/group/nested/repo/-/releases/tag")
    check(releasePageFromArtifact("https://cdn.example.com/archived/bundle.rvp") == null)
    check(releasePageFromArtifact("javascript:alert(1)") == null)
    check(bundleRepositoryUrl("git@github.com:MorpheApp/morphe-patches.git") ==
        "https://github.com/MorpheApp/morphe-patches")
    check(bundleRepositoryUrl("https://api.github.com/repos/a/b/releases/assets/15") == "https://github.com/a/b") {
        "Ktor URL parser: " + runCatching { io.ktor.http.Url("https://api.github.com/repos/a/b/releases/assets/15") }.exceptionOrNull()
    }
    check(repositoryReleasePage("https://github.com/a/b", "v1/beta tag") ==
        "https://github.com/a/b/releases/tag/v1%2Fbeta%20tag")
    check(repositoryReleasePage("https://gitlab.com/group/repo", "v1") ==
        "https://gitlab.com/group/repo/-/releases/v1")
    check("javascript:alert(1)".safeReleasePage() == null)
    check(bundleReleaseVersion("url:https://cdn.example.com/b.rvp", "1.2") == "1.2")
    check(bundleReleaseVersion("sha256:abcdef", "1.2") == "1.2")
    check(bundleReleaseVersion("v1.2", "1.1") == "v1.2")
    println("Release URL contracts passed: exact tags, encoded tags, GitHub/GitLab, SSH sources, archived URLs.")
}

fun main(args: Array<String>) = runBlocking {
    contracts()
    geometryContracts()
    releaseUrlContracts()
    if ("--live" in args || "--live-scoped" in args) live("--live-scoped" in args)
}

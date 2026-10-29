package app.universal.patch.manager.network.api

import app.universal.patch.manager.network.dto.PackageNode
import app.universal.patch.manager.network.utils.APIFailure
import app.universal.patch.manager.network.utils.APIResponse
import app.universal.patch.manager.network.utils.getOrThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.IOException
import java.security.MessageDigest

internal class CatalogRequestBudget(var remaining: Int = 64)

@Serializable
internal data class CatalogApp(
    @SerialName("package_name") val packageName: String,
    @SerialName("app_name") val appName: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    val bundles: List<CatalogAppBundle> = emptyList(),
)

@Serializable
internal data class CatalogAppBundle(
    @SerialName("bundle_id") val bundleId: String,
    val versions: List<String> = emptyList(),
    val unrestricted: Boolean = false,
)

internal fun CatalogFilters.appBundleIds(app: CatalogApp): List<String> {
    val packageQuery = packageName.substringBefore('@').trim()
    if (packageQuery.isNotBlank() && if (exactPackage) app.packageName != packageQuery
        else !app.packageName.contains(packageQuery, true)) return emptyList()
    if (query.isNotBlank() && !app.packageName.contains(query.trim(), true) &&
        app.appName?.contains(query.trim(), true) != true) return emptyList()
    val appVersion = packageName.substringAfter('@', "").takeIf(String::isNotBlank)
    return app.bundles.filter { appVersion == null || it.unrestricted || appVersion in it.versions }
        .map { it.bundleId }.distinct().sorted()
}

/** Browse the precomputed compatibility index, never the global patch relationships. */
internal class CatalogAppsIndex(
    private val request: suspend (String, JsonObject, Boolean, String, CatalogRequestBudget) -> APIResponse<JsonObject>,
) {
    private val mutex = Mutex()
    private val qualified = linkedMapOf<String, Boolean>()
    private val bundleMatches = linkedMapOf<String, Boolean>()
    private val scopedBundles = linkedMapOf<String, Set<String>>()
    private val pageGenerations = linkedMapOf<CatalogFilters, String>()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getApps(filters: CatalogFilters, offset: Int, force: Boolean): APIResponse<List<PackageNode>> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val rows = try {
                        read(filters, offset.coerceAtLeast(0), force,
                            if (offset > 0) pageGenerations[filters] else null)
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        if (offset != 0 || !failure.message.orEmpty().contains("Catalog changed", true)) throw failure
                        read(filters, 0, true, null)
                    }
                    rows.generation?.let { pageGenerations[filters] = it }
                    while (pageGenerations.size > 64) pageGenerations.remove(pageGenerations.keys.first())
                    APIResponse.Success(rows.apps)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    APIResponse.Failure(APIFailure(IOException(
                        failure.message ?: "Could not load apps"), null))
                }
            }
        }

    suspend fun matchingBundles(filters: CatalogFilters, candidates: List<app.universal.patch.manager.network.dto.BundleNode>,
        expectedGeneration: String?, force: Boolean): Set<Int> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val byCatalogId = candidates.filter { it.hasCurrentPatchMetadata() }
                .associateBy { it.catalogId }
            if (byCatalogId.isEmpty()) return@withLock emptySet()
            val found = mutableSetOf<Int>()
            val budget = CatalogRequestBudget()
            var generation = expectedGeneration
            var offset = 0
            while (true) {
                val variables = buildJsonObject {
                    put("offset", offset); put("limit", INDEX_PAGE_SIZE)
                    put("generation", if (offset == 0) JsonNull else generation?.let(::JsonPrimitive) ?: JsonNull)
                }
                var page = request(INDEX_QUERY, variables, force, "", budget).getOrThrow()
                var current = page.getValue("catalog_generation").jsonPrimitive.content
                if (offset == 0 && generation != null && current != generation && !force) {
                    page = request(INDEX_QUERY, variables, true, "", budget).getOrThrow()
                    current = page.getValue("catalog_generation").jsonPrimitive.content
                }
                check(generation == null || current == generation) { "Catalog changed; refresh the catalog" }
                generation = current
                val rows = page.getValue("historical_apps").jsonArray
                    .map { json.decodeFromJsonElement<CatalogApp>(it) }
                rows.forEach { app ->
                    filters.appBundleIds(app).mapNotNullTo(found) { byCatalogId[it]?.id }
                }
                if (rows.size < INDEX_PAGE_SIZE || found.size == byCatalogId.size) break
                offset += rows.size
            }
            found
        }
    }

    private data class AppPage(val generation: String?, val apps: List<PackageNode>)

    private suspend fun read(filters: CatalogFilters, offset: Int, force: Boolean,
        expectedGeneration: String?): AppPage {
        val budget = CatalogRequestBudget()
        var generation = expectedGeneration
        var indexOffset = 0
        val result = mutableListOf<PackageNode>()
        var skipped = 0
        val scope = buildJsonObject {
            filters.copy(query = "", packageName = "").bundleWhere(currentOnly = false)
                .forEach { (key, value) -> put(key, value) }
            put("metadata_status", buildJsonObject { put("_eq", "verified") })
            put("need_patches_update", buildJsonObject { put("_eq", false) })
            put("patcher_failure_fingerprint", buildJsonObject { put("_is_null", true) })
        }
        while (result.size < 30) {
            val page = request(INDEX_QUERY, buildJsonObject {
                put("offset", indexOffset); put("limit", INDEX_PAGE_SIZE)
                put("generation", if (indexOffset == 0) JsonNull else generation?.let(::JsonPrimitive) ?: JsonNull)
            }, force, "", budget).getOrThrow()
            val current = page.getValue("catalog_generation").jsonPrimitive.content
            check(generation == null || current == generation) { "Catalog changed; refresh the catalog" }
            generation = current
            val rows = page.getValue("historical_apps").jsonArray
                .map { json.decodeFromJsonElement<CatalogApp>(it) }
            val candidates = rows.mapNotNull { app ->
                filters.appBundleIds(app).takeIf(List<String>::isNotEmpty)?.let { ids ->
                    Candidate(app, ids, digest("$current|$scope|${ids.joinToString(",")}"))
                }
            }
            if (candidates.isNotEmpty() && (filters.release == "latest" ||
                filters.sourceUrl.isNotBlank() || filters.version.isNotBlank())) {
                val ids = scopedBundleIds(scope, current, force, budget)
                candidates.forEach { qualified[it.key] = it.ids.any(ids::contains) }
            } else qualify(candidates, scope, current, force, budget)
            for (candidate in candidates) {
                if (qualified[candidate.key] != true) continue
                if (skipped++ < offset) continue
                result += PackageNode(name = candidate.app.packageName,
                    appName = candidate.app.appName, iconUrl = candidate.app.iconUrl)
                if (result.size == 30) break
            }
            while (qualified.size > 512) qualified.remove(qualified.keys.first())
            if (rows.size < INDEX_PAGE_SIZE) break
            indexOffset += rows.size
        }
        return AppPage(generation, result)
    }

    private suspend fun scopedBundleIds(scope: JsonObject, generation: String,
        force: Boolean, budget: CatalogRequestBudget): Set<String> {
        val key = "$generation|$scope"
        scopedBundles[key]?.let { return it }
        val result = mutableSetOf<String>()
        var offset = 0
        while (true) {
            val page = request(SCOPE_QUERY, buildJsonObject {
                put("where", scope); put("offset", offset)
            }, force, generation, budget).getOrThrow()
            check(page.getValue("catalog_generation").jsonPrimitive.content == generation) {
                "Catalog changed; refresh the catalog"
            }
            val rows = page.getValue("bundle").jsonArray
            result += rows.map { it.jsonObject.getValue("catalog_id").jsonPrimitive.content }
            if (rows.size < 100) break
            offset += rows.size
        }
        scopedBundles[key] = result
        while (scopedBundles.size > 8) scopedBundles.remove(scopedBundles.keys.first())
        return result
    }

    private suspend fun qualify(candidates: List<Candidate>, scope: JsonObject,
        generation: String, force: Boolean, budget: CatalogRequestBudget) {
        val pending = candidates.distinctBy { it.key }.filter { it.key !in qualified }
        val prefix = "$generation|$scope|"
        // Apps frequently share bundles. Resolve each catalog ID once rather than
        // searching each app's compatibility relationships or all source history.
        val chunks = pending.flatMap { it.ids }.distinct()
            .filter { prefix + it !in bundleMatches }.chunked(70)
        for (batch in chunks.chunked(4)) {
            val declarations = mutableListOf("\$scope: bundle_bool_exp!")
            val fields = mutableListOf("catalog_generation")
            val variables = buildJsonObject {
                put("scope", scope)
                batch.forEachIndexed { index, ids ->
                    declarations += "\$ids$index: [String!]!"
                    fields += "m$index: bundle(where: {_and: [\$scope, {catalog_id: {_in: \$ids$index}}]}, " +
                        "limit: 70, distinct_on: [id], order_by: {id: asc}) { catalog_id }"
                    put("ids$index", JsonArray(ids.map(::JsonPrimitive)))
                }
            }
            val query = "query AppBundleMatches(${declarations.joinToString(",")}) { ${fields.joinToString(" ")} }"
            val data = request(query, variables, force, generation, budget).getOrThrow()
            check(data.getValue("catalog_generation").jsonPrimitive.content == generation) {
                "Catalog changed; refresh the catalog"
            }
            batch.forEachIndexed { index, ids ->
                val matches = data.getValue("m$index").jsonArray
                    .map { it.jsonObject.getValue("catalog_id").jsonPrimitive.content }.toSet()
                ids.forEach { bundleMatches[prefix + it] = it in matches }
            }
        }
        pending.forEach { candidate ->
            qualified[candidate.key] = candidate.ids.any { bundleMatches[prefix + it] == true }
        }
        while (bundleMatches.size > 8192) bundleMatches.remove(bundleMatches.keys.first())
    }

    private data class Candidate(val app: CatalogApp, val ids: List<String>, val key: String)
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val INDEX_PAGE_SIZE = 100
        private const val SCOPE_QUERY = """
            query AppScopeBundles(${"$"}where: bundle_bool_exp!, ${"$"}offset: Int!) {
              catalog_generation
              bundle(where: ${"$"}where, limit: 100, offset: ${"$"}offset,
                     distinct_on: [id], order_by: {id: asc}) { catalog_id }
            }
        """
        private const val INDEX_QUERY = """
            query CatalogAppIndex(${"$"}offset: Int!, ${"$"}limit: Int!, ${"$"}generation: String) {
              catalog_generation
              historical_apps(limit: ${"$"}limit, offset: ${"$"}offset, generation: ${"$"}generation)
            }
        """
    }
}

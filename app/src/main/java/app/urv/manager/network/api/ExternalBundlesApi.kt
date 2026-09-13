package app.urv.manager.network.api

import app.urv.manager.network.dto.BundleNode
import app.urv.manager.network.dto.BundlesQueryData
import app.urv.manager.network.dto.ExternalBundlePatch
import app.urv.manager.network.dto.ExternalBundlePackage
import app.urv.manager.network.dto.ExternalBundleSnapshot
import app.urv.manager.network.dto.GraphqlError
import app.urv.manager.network.dto.GraphqlRequest
import app.urv.manager.network.dto.GraphqlResponse
import app.urv.manager.network.dto.PatchNode
import app.urv.manager.network.dto.RefreshJobNode
import app.urv.manager.network.service.HttpService
import app.urv.manager.network.utils.APIFailure
import app.urv.manager.network.utils.APIResponse
import app.urv.manager.network.utils.transform
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

data class ExternalBundlesPage(
    val bundles: List<ExternalBundleSnapshot>,
    val apiHost: String,
    val refreshJob: RefreshJobNode?
)

class ExternalBundlesApi(
    private val client: HttpService,
) {
    private val endpointSelectionMutex = Mutex()
    @Volatile
    private var cachedEndpointSelection: CachedEndpointSelection? = null

    suspend fun getBundles(
        packageNameQuery: String? = null,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0,
        apiHost: String? = null
    ): APIResponse<ExternalBundlesPage> {
        val variables = buildBundleVariables(packageNameQuery, limit, offset)
        endpointForHost(apiHost.orEmpty())?.let { endpoint ->
            return graphqlWithDeadline<BundlesQueryData>(
                endpoint.graphqlUrl,
                BUNDLES_QUERY,
                variables
            ).transform { data -> data.toDiscoveryPage(endpoint) }
        }
        return queryPreferred<BundlesQueryData, ExternalBundlesPage>(
            BUNDLES_QUERY,
            variables
        ) { data, endpoint -> data.toDiscoveryPage(endpoint) }
    }

    suspend fun getBundleById(
        bundleId: Int,
        apiHost: String = ExternalBundlesEndpoints.STABLE_HOST
    ): APIResponse<ExternalBundleSnapshot?> {
        val variables = buildJsonObject {
            put("id", JsonPrimitive(bundleId))
        }
        val endpoint = endpointForHost(apiHost) ?: STABLE_ENDPOINT
        return graphqlWithDeadline<BundlesQueryData>(
            endpoint.graphqlUrl,
            BUNDLE_BY_ID_QUERY,
            variables
        ).transform { data ->
            data.bundle.firstOrNull()?.toSnapshot(endpoint.host)
        }
    }

    suspend fun getLatestBundle(
        owner: String,
        repo: String,
        prerelease: Boolean
    ): APIResponse<ExternalBundleSnapshot?> {
        val trimmedOwner = owner.trim()
        val trimmedRepo = repo.trim()
        if (trimmedOwner.isBlank() || trimmedRepo.isBlank()) {
            return APIResponse.Success(null)
        }
        val variables = buildJsonObject {
            put("owner", JsonPrimitive(trimmedOwner))
            put("repo", JsonPrimitive(trimmedRepo))
            put("prerelease", JsonPrimitive(prerelease))
        }
        return queryPreferred<BundlesQueryData, ExternalBundleSnapshot?>(
            BUNDLE_LATEST_QUERY,
            variables
        ) { data, endpoint ->
            data.bundle.firstOrNull()?.toSnapshot(endpoint.host)
        }
    }

    suspend fun getLatestBundleAny(
        owner: String,
        repo: String
    ): APIResponse<ExternalBundleSnapshot?> {
        val trimmedOwner = owner.trim()
        val trimmedRepo = repo.trim()
        if (trimmedOwner.isBlank() || trimmedRepo.isBlank()) {
            return APIResponse.Success(null)
        }
        val variables = buildJsonObject {
            put("owner", JsonPrimitive(trimmedOwner))
            put("repo", JsonPrimitive(trimmedRepo))
        }
        return queryPreferred<BundlesQueryData, ExternalBundleSnapshot?>(
            BUNDLE_LATEST_ANY_QUERY,
            variables
        ) { data, endpoint ->
            data.bundle.firstOrNull()?.toSnapshot(endpoint.host)
        }
    }

    suspend fun getBundleHistory(
        owner: String,
        repo: String,
        prerelease: Boolean? = null,
        limit: Int = DEFAULT_PAGE_SIZE
    ): APIResponse<List<ExternalBundleSnapshot>> {
        val trimmedOwner = owner.trim()
        val trimmedRepo = repo.trim()
        if (trimmedOwner.isBlank() || trimmedRepo.isBlank()) {
            return APIResponse.Success(emptyList())
        }

        val targetLimit = limit.coerceAtLeast(1)
        val preferredEndpoint = preferredEndpoint()
        val preferredResponse = getBundleHistory(
            preferredEndpoint,
            trimmedOwner,
            trimmedRepo,
            prerelease,
            targetLimit
        )
        if (preferredResponse is APIResponse.Success) return preferredResponse

        val fallbackEndpoint = alternateEndpoint(preferredEndpoint)
        return getBundleHistory(
            fallbackEndpoint,
            trimmedOwner,
            trimmedRepo,
            prerelease,
            targetLimit
        ).also { response ->
            if (response is APIResponse.Success) cacheDegradedEndpoint(fallbackEndpoint)
        }
    }

    private suspend fun getBundleHistory(
        endpoint: Endpoint,
        owner: String,
        repo: String,
        prerelease: Boolean?,
        limit: Int
    ): APIResponse<List<ExternalBundleSnapshot>> {
        val history = mutableListOf<ExternalBundleSnapshot>()
        var offset = 0

        while (history.size < limit) {
            val pageLimit = minOf(DEFAULT_PAGE_SIZE, limit - history.size)
            val variables = buildBundleHistoryVariables(
                owner = owner,
                repo = repo,
                limit = pageLimit,
                offset = offset,
                prerelease = prerelease
            )
            when (val response = graphqlWithDeadline<BundlesQueryData>(endpoint.graphqlUrl, BUNDLES_QUERY, variables)) {
                is APIResponse.Success -> {
                    val batch = response.data.bundle.map { it.toSnapshot(endpoint.host) }
                    history += batch
                    if (batch.size < pageLimit) break
                    offset += batch.size
                }
                is APIResponse.Error -> return APIResponse.Error(response.error)
                is APIResponse.Failure -> return APIResponse.Failure(response.error)
            }
        }

        return APIResponse.Success(history.take(limit))
    }

    suspend fun getBundlePatches(bundle: ExternalBundleSnapshot): APIResponse<List<ExternalBundlePatch>> {
        val variables = buildJsonObject {
            put("id", JsonPrimitive(bundle.bundleId))
        }
        val preferredEndpoint = endpointForHost(bundle.apiHost) ?: STABLE_ENDPOINT
        val fallbackEndpoint = if (preferredEndpoint == STABLE_ENDPOINT) DEV_ENDPOINT else STABLE_ENDPOINT
        val preferredResponse = graphqlWithDeadline<BundlesQueryData>(
            preferredEndpoint.graphqlUrl,
            BUNDLE_PATCHES_QUERY,
            variables
        )
        val preferredPatches = (preferredResponse as? APIResponse.Success)
            ?.data
            ?.bundle
            ?.firstOrNull()
            ?.patches
            ?.map { it.toPatch() }
        if (preferredPatches != null && (preferredPatches.isNotEmpty() || bundle.patchCount <= 0)) {
            return APIResponse.Success(preferredPatches)
        }

        val fallbackVariables = buildBundleIdentityVariables(bundle)
            ?: return preferredPatches?.let { APIResponse.Success(it) }
                ?: preferredResponse.transform { data ->
                    data.bundle.firstOrNull()?.patches?.map { it.toPatch() }.orEmpty()
                }
        val fallbackResponse = graphqlWithDeadline<BundlesQueryData>(
            fallbackEndpoint.graphqlUrl,
            BUNDLE_PATCHES_BY_IDENTITY_QUERY,
            fallbackVariables
        )
        if (fallbackResponse is APIResponse.Success) {
            val fallbackPatches = fallbackResponse.data.bundle
                .firstOrNull()
                ?.patches
                ?.map { it.toPatch() }
                .orEmpty()
            if (fallbackPatches.isNotEmpty() || preferredPatches == null) {
                return APIResponse.Success(fallbackPatches)
            }
        }

        preferredPatches?.let { return APIResponse.Success(it) }

        return when (fallbackResponse) {
            is APIResponse.Error -> APIResponse.Error(fallbackResponse.error)
            is APIResponse.Failure -> APIResponse.Failure(fallbackResponse.error)
            is APIResponse.Success -> APIResponse.Success(emptyList())
        }
    }

    private suspend inline fun <reified T> graphql(
        endpointUrl: String,
        query: String,
        variables: JsonObject? = null,
    ): APIResponse<T> {
        val response = client.request<GraphqlResponse<T>> {
            method = HttpMethod.Post
            url(endpointUrl)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(GraphqlRequest(query = query, variables = variables))
        }

        return when (response) {
            is APIResponse.Success -> {
                val payload = response.data
                val errors = payload.errors.orEmpty().mapNotNull(GraphqlError::message).filter { it.isNotBlank() }
                when {
                    errors.isNotEmpty() ->
                        APIResponse.Failure(APIFailure(GraphqlException(errors.joinToString("\n")), null))
                    payload.data == null ->
                        APIResponse.Failure(APIFailure(GraphqlException("GraphQL response missing data"), null))
                    else -> APIResponse.Success(payload.data)
                }
            }
            is APIResponse.Error -> APIResponse.Error(response.error)
            is APIResponse.Failure -> APIResponse.Failure(response.error)
        }
    }

    private suspend inline fun <reified T> graphqlWithDeadline(
        endpointUrl: String,
        query: String,
        variables: JsonObject? = null,
    ): APIResponse<T> = withTimeoutOrNull(ExternalBundlesEndpoints.HOST_QUERY_TIMEOUT_MS) {
        graphql<T>(endpointUrl, query, variables)
    } ?: APIResponse.Failure(
        APIFailure(IOException("Timed out querying $endpointUrl"), null)
    )

    private fun BundleNode.toSnapshot(bundlesHost: String): ExternalBundleSnapshot {
        val metadata = source?.sourceMetadata
        val bundleTypeValue = bundleType?.trim().orEmpty()
        val patchCount = patchesAggregate?.aggregate?.count
            ?: patches?.size
            ?: 0
        val resolved = resolveBundleMetadata(
            bundleTypeValue,
            rawVersion = version
        )
        val normalizedDownloadUrl = normalizeExternalBundlesUrl(downloadUrl, bundlesHost)
        val normalizedSignatureUrl = normalizeExternalBundlesUrl(signatureDownloadUrl, bundlesHost)

        return ExternalBundleSnapshot(
            apiHost = bundlesHost,
            ownerName = metadata?.ownerName.orEmpty(),
            ownerAvatarUrl = metadata?.ownerAvatarUrl,
            repoName = metadata?.repoName.orEmpty(),
            repoDescription = metadata?.repoDescription,
            sourceUrl = source?.url.orEmpty(),
            repoStars = metadata?.repoStars ?: 0,
            repoPushedAt = metadata?.repoPushedAt,
            lastRefreshedAt = null,
            isRepoArchived = metadata?.isRepoArchived ?: false,
            bundleId = id,
            bundleType = resolved.bundleType,
            createdAt = createdAt.orEmpty(),
            description = description,
            version = resolved.version,
            downloadUrl = normalizedDownloadUrl,
            signatureDownloadUrl = normalizedSignatureUrl,
            isPrerelease = isPrerelease,
            isBundleV3 = resolved.isBundleV3,
            patchCount = patchCount,
            patches = patches?.map { it.toPatch() }.orEmpty(),
        )
    }

    private fun PatchNode.toPatch(): ExternalBundlePatch {
        val packages = patchPackages.mapNotNull { packageNode ->
            val pkg = packageNode.pkg ?: return@mapNotNull null
            val name = pkg.name?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            ExternalBundlePackage(
                name = name,
                versions = listOf(pkg.version)
            )
        }.groupBy { it.name }.map { (name, entries) ->
            ExternalBundlePackage(
                name = name,
                versions = entries.flatMap { it.versions }.distinct()
            )
        }

        return ExternalBundlePatch(
            name = name,
            description = description,
            compatiblePackages = packages,
        )
    }

    private data class ResolvedBundleMetadata(
        val bundleType: String,
        val isBundleV3: Boolean,
        val version: String
    )

    private fun resolveBundleMetadata(
        bundleType: String?,
        rawVersion: String?
    ): ResolvedBundleMetadata {
        val normalizedType = bundleType?.trim().orEmpty()
        val version = rawVersion?.trim().orEmpty()
        val isBundleV3 = normalizedType.contains("v3", ignoreCase = true)
        return ResolvedBundleMetadata(
            bundleType = normalizedType,
            isBundleV3 = isBundleV3,
            version = version
        )
    }

    private fun normalizeExternalBundlesUrl(raw: String?, bundlesHost: String): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        val normalizedPath = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return "https://$bundlesHost$normalizedPath"
    }

    private fun buildBundleVariables(
        packageNameQuery: String?,
        limit: Int,
        offset: Int
    ): JsonObject {
        val trimmed = packageNameQuery?.trim().orEmpty()
        return if (trimmed.isEmpty()) {
            buildJsonObject {
                put("where", buildJsonObject { })
                put("limit", JsonPrimitive(limit))
                put("offset", JsonPrimitive(offset))
            }
        } else {
            buildJsonObject {
                put("where", buildJsonObject {
                    put("patches", buildJsonObject {
                        put("patch_packages", buildJsonObject {
                            put("package", buildJsonObject {
                                put("name", buildJsonObject {
                                    put("_ilike", JsonPrimitive("%$trimmed%"))
                                })
                            })
                        })
                    })
                })
                put("limit", JsonPrimitive(limit))
                put("offset", JsonPrimitive(offset))
            }
        }
    }

    private fun buildBundleHistoryVariables(
        owner: String,
        repo: String,
        limit: Int,
        offset: Int,
        prerelease: Boolean?
    ): JsonObject = buildJsonObject {
        put("where", buildJsonObject {
            put("source", buildJsonObject {
                put("source_metadatum", buildJsonObject {
                    put("owner_name", buildJsonObject { put("_eq", JsonPrimitive(owner)) })
                    put("repo_name", buildJsonObject { put("_eq", JsonPrimitive(repo)) })
                })
            })
            prerelease?.let {
                put("is_prerelease", buildJsonObject { put("_eq", JsonPrimitive(it)) })
            }
        })
        put("limit", JsonPrimitive(limit))
        put("offset", JsonPrimitive(offset))
    }

    private fun BundlesQueryData.toDiscoveryPage(endpoint: Endpoint) = ExternalBundlesPage(
        bundles = bundle.map { it.toSnapshot(endpoint.host) },
        apiHost = endpoint.host,
        refreshJob = refreshJobs.firstOrNull()
    )

    private fun buildBundleIdentityVariables(bundle: ExternalBundleSnapshot): JsonObject? {
        val sourceUrl = bundle.sourceUrl.trim().removeSuffix("/")
        val version = bundle.version.trim()
        if (sourceUrl.isBlank() || version.isBlank()) return null
        return buildJsonObject {
            put("sourceUrls", buildJsonArray {
                add(JsonPrimitive(sourceUrl))
                add(JsonPrimitive("$sourceUrl/"))
            })
            put("version", JsonPrimitive(version))
            put("prerelease", JsonPrimitive(bundle.isPrerelease))
        }
    }

    private data class Endpoint(
        val host: String,
        val graphqlUrl: String,
        val apiDocumentUrl: String
    )

    private data class ResolvedEndpointSelection(
        val endpoint: Endpoint,
        val cacheDurationMillis: Long
    )

    private data class CachedEndpointSelection(
        val endpoint: Endpoint,
        val resolvedAtMillis: Long,
        val cacheDurationMillis: Long
    )

    @Serializable
    private data class ApiDocument(
        val info: ApiInfo
    )

    @Serializable
    private data class ApiInfo(
        val version: String
    )

    private suspend inline fun <reified T, R> queryPreferred(
        query: String,
        variables: JsonObject?,
        crossinline transform: (T, Endpoint) -> R
    ): APIResponse<R> {
        val preferredEndpoint = preferredEndpoint()
        val preferredResponse = graphqlWithDeadline<T>(
            preferredEndpoint.graphqlUrl,
            query,
            variables
        )
        if (preferredResponse is APIResponse.Success) {
            return APIResponse.Success(transform(preferredResponse.data, preferredEndpoint))
        }

        val fallbackEndpoint = alternateEndpoint(preferredEndpoint)
        return graphqlWithDeadline<T>(fallbackEndpoint.graphqlUrl, query, variables)
            .transform { data -> transform(data, fallbackEndpoint) }
            .also { response ->
                if (response is APIResponse.Success) cacheDegradedEndpoint(fallbackEndpoint)
            }
    }

    private suspend fun preferredEndpoint(): Endpoint {
        cachedEndpoint()?.let { return it }
        endpointSelectionMutex.lock()
        return try {
            cachedEndpoint() ?: resolvePreferredEndpoint().let { selection ->
                cachedEndpointSelection = CachedEndpointSelection(
                    endpoint = selection.endpoint,
                    resolvedAtMillis = monotonicMillis(),
                    cacheDurationMillis = selection.cacheDurationMillis
                )
                selection.endpoint
            }
        } finally {
            endpointSelectionMutex.unlock()
        }
    }

    private fun cachedEndpoint(): Endpoint? {
        val selection = cachedEndpointSelection ?: return null
        val ageMillis = monotonicMillis() - selection.resolvedAtMillis
        return selection.endpoint.takeIf { ageMillis < selection.cacheDurationMillis }
    }

    private suspend fun cacheDegradedEndpoint(endpoint: Endpoint) {
        endpointSelectionMutex.lock()
        try {
            cachedEndpointSelection = CachedEndpointSelection(
                endpoint = endpoint,
                resolvedAtMillis = monotonicMillis(),
                cacheDurationMillis = DEGRADED_ENDPOINT_SELECTION_TTL_MS
            )
        } finally {
            endpointSelectionMutex.unlock()
        }
    }

    private suspend fun resolvePreferredEndpoint(): ResolvedEndpointSelection = coroutineScope {
        val stableVersion = async { apiVersion(STABLE_ENDPOINT) }
        val devVersion = async { apiVersion(DEV_ENDPOINT) }
        val stableVersionValue = stableVersion.await()
        val devVersionValue = devVersion.await()
        val endpoint = endpointForHost(
            ExternalBundlesEndpoints.preferredHost(stableVersionValue, devVersionValue)
        ) ?: DEV_ENDPOINT
        ResolvedEndpointSelection(
            endpoint = endpoint,
            cacheDurationMillis = if (stableVersionValue != null && devVersionValue != null) {
                ENDPOINT_SELECTION_TTL_MS
            } else {
                DEGRADED_ENDPOINT_SELECTION_TTL_MS
            }
        )
    }

    private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

    private suspend fun apiVersion(endpoint: Endpoint): String? =
        withTimeoutOrNull(ExternalBundlesEndpoints.HOST_QUERY_TIMEOUT_MS) {
            when (val response = client.request<ApiDocument> {
                method = HttpMethod.Get
                url(endpoint.apiDocumentUrl)
            }) {
                is APIResponse.Success -> response.data.info.version.trim().takeIf(String::isNotBlank)
                is APIResponse.Error,
                is APIResponse.Failure -> null
            }
        }

    private fun alternateEndpoint(endpoint: Endpoint): Endpoint =
        if (endpoint == STABLE_ENDPOINT) DEV_ENDPOINT else STABLE_ENDPOINT

    private fun endpointForHost(host: String): Endpoint? = when {
        host.equals(STABLE_ENDPOINT.host, ignoreCase = true) -> STABLE_ENDPOINT
        host.equals(DEV_ENDPOINT.host, ignoreCase = true) -> DEV_ENDPOINT
        else -> null
    }

    companion object {
        private val STABLE_ENDPOINT = Endpoint(
            host = ExternalBundlesEndpoints.STABLE_HOST,
            graphqlUrl = "https://${ExternalBundlesEndpoints.STABLE_HOST}/hasura/v1/graphql",
            apiDocumentUrl = "https://${ExternalBundlesEndpoints.STABLE_HOST}/api.json"
        )
        private val DEV_ENDPOINT = Endpoint(
            host = ExternalBundlesEndpoints.DEV_HOST,
            graphqlUrl = "https://${ExternalBundlesEndpoints.DEV_HOST}/hasura/v1/graphql",
            apiDocumentUrl = "https://${ExternalBundlesEndpoints.DEV_HOST}/api.json"
        )
        private const val ENDPOINT_SELECTION_TTL_MS = 15 * 60 * 1_000L
        private const val DEGRADED_ENDPOINT_SELECTION_TTL_MS = 30 * 1_000L
        private const val BUNDLES_QUERY = """
            query BundleDiscovery(${"$"}where: bundle_bool_exp, ${"$"}limit: Int, ${"$"}offset: Int) {
              refresh_jobs(
                where: { status: { _eq: "COMPLETED" } }
                order_by: { started_at: desc }
                limit: 1
              ) {
                started_at
                completed_at
                status
              }
              bundle(
                where: ${"$"}where
                order_by: { created_at: desc }
                limit: ${"$"}limit
                offset: ${"$"}offset
              ) {
                id
                bundle_type
                created_at
                description
                download_url
                signature_download_url
                is_prerelease
                version
                source {
                  url
                  source_metadatum {
                    owner_name
                    owner_avatar_url
                    repo_name
                    repo_description
                    repo_stars
                    repo_pushed_at
                    is_repo_archived
                  }
                }
                patches_aggregate {
                  aggregate {
                    count
                  }
                }
              }
            }
        """
        private const val BUNDLE_PATCHES_QUERY = """
            query BundlePatches(${"$"}id: Int!) {
              bundle(where: { id: { _eq: ${"$"}id } }) {
                id
                patches {
                  name
                  description
                  patch_packages {
                    package {
                      name
                      version
                    }
                  }
                }
              }
            }
        """
        private const val BUNDLE_PATCHES_BY_IDENTITY_QUERY = """
            query BundlePatchesByIdentity(
              ${"$"}sourceUrls: [String!]!
              ${"$"}version: String!
              ${"$"}prerelease: Boolean!
            ) {
              bundle(
                where: {
                  source: { url: { _in: ${"$"}sourceUrls } }
                  version: { _eq: ${"$"}version }
                  is_prerelease: { _eq: ${"$"}prerelease }
                }
                order_by: { created_at: desc }
                limit: 1
              ) {
                id
                patches {
                  name
                  description
                  patch_packages {
                    package {
                      name
                      version
                    }
                  }
                }
              }
            }
        """
        private const val BUNDLE_BY_ID_QUERY = """
            query BundleById(${"$"}id: Int!) {
              bundle(where: { id: { _eq: ${"$"}id } }) {
                id
                bundle_type
                created_at
                description
                download_url
                signature_download_url
                is_prerelease
                version
                source {
                  url
                  source_metadatum {
                    owner_name
                    owner_avatar_url
                    repo_name
                    repo_description
                    repo_stars
                    repo_pushed_at
                    is_repo_archived
                  }
                }
                patches_aggregate {
                  aggregate {
                    count
                  }
                }
              }
            }
        """
        private const val BUNDLE_LATEST_QUERY = """
            query BundleLatest(${"$"}owner: String!, ${"$"}repo: String!, ${"$"}prerelease: Boolean!) {
              bundle(
                where: {
                  is_prerelease: { _eq: ${"$"}prerelease }
                  source: {
                    source_metadatum: {
                      owner_name: { _eq: ${"$"}owner }
                      repo_name: { _eq: ${"$"}repo }
                    }
                  }
                }
                order_by: { created_at: desc }
                limit: 1
              ) {
                id
                bundle_type
                created_at
                description
                download_url
                signature_download_url
                is_prerelease
                version
                source {
                  url
                  source_metadatum {
                    owner_name
                    owner_avatar_url
                    repo_name
                    repo_description
                    repo_stars
                    repo_pushed_at
                    is_repo_archived
                  }
                }
                patches_aggregate {
                  aggregate {
                    count
                  }
                }
              }
            }
        """
        private const val BUNDLE_LATEST_ANY_QUERY = """
            query BundleLatestAny(${"$"}owner: String!, ${"$"}repo: String!) {
              bundle(
                where: {
                  source: {
                    source_metadatum: {
                      owner_name: { _eq: ${"$"}owner }
                      repo_name: { _eq: ${"$"}repo }
                    }
                  }
                }
                order_by: { created_at: desc }
                limit: 1
              ) {
                id
                bundle_type
                created_at
                description
                download_url
                signature_download_url
                is_prerelease
                version
                source {
                  url
                  source_metadatum {
                    owner_name
                    owner_avatar_url
                    repo_name
                    repo_description
                    repo_stars
                    repo_pushed_at
                    is_repo_archived
                  }
                }
                patches_aggregate {
                  aggregate {
                    count
                  }
                }
              }
            }
        """
        private const val DEFAULT_PAGE_SIZE = 30
    }

    private class GraphqlException(message: String) : Exception(message)
}

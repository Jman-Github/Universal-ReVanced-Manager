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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
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
        val responses = queryBoth<BundlesQueryData>(BUNDLES_QUERY, variables)
        return selectFreshestResponse(
            responses = responses,
            timestamp = { data -> data.bundle.firstOrNull()?.createdAt },
            transform = { data, endpoint ->
                data.toDiscoveryPage(endpoint)
            },
            preferDev = ::isDevDiscoveryResponseFresher
        )
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
        val responses = queryBoth<BundlesQueryData>(BUNDLE_LATEST_QUERY, variables)
        return selectFreshestResponse(
            responses = responses,
            timestamp = { data -> data.bundle.firstOrNull()?.createdAt },
            transform = { data, endpoint ->
                data.bundle.firstOrNull()?.toSnapshot(endpoint.host)
            }
        )
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
        val responses = queryBoth<BundlesQueryData>(BUNDLE_LATEST_ANY_QUERY, variables)
        return selectFreshestResponse(
            responses = responses,
            timestamp = { data -> data.bundle.firstOrNull()?.createdAt },
            transform = { data, endpoint ->
                data.bundle.firstOrNull()?.toSnapshot(endpoint.host)
            }
        )
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
        val responses = coroutineScope {
            val stable = async {
                getBundleHistory(STABLE_ENDPOINT, trimmedOwner, trimmedRepo, prerelease, targetLimit)
            }
            val dev = async {
                getBundleHistory(DEV_ENDPOINT, trimmedOwner, trimmedRepo, prerelease, targetLimit)
            }
            EndpointResponses(stable.await(), dev.await())
        }
        val stable = responses.stable
        val dev = responses.dev
        return when {
            stable is APIResponse.Success && dev is APIResponse.Success -> {
                val history = (stable.data + dev.data)
                    .sortedWith { left, right ->
                        when {
                            isNewer(left.createdAt, right.createdAt) -> -1
                            isNewer(right.createdAt, left.createdAt) -> 1
                            else -> 0
                        }
                    }
                    .distinctBy { snapshot ->
                        listOf(
                            snapshot.sourceUrl.trim().lowercase(),
                            snapshot.version.trim().lowercase(),
                            snapshot.isPrerelease.toString()
                        ).joinToString("|")
                    }
                    .take(targetLimit)
                APIResponse.Success(history)
            }
            stable is APIResponse.Success -> stable
            dev is APIResponse.Success -> dev
            dev is APIResponse.Error -> APIResponse.Error(dev.error)
            dev is APIResponse.Failure -> APIResponse.Failure(dev.error)
            else -> APIResponse.Success(emptyList())
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
        val graphqlUrl: String
    )

    private data class EndpointResponses<T>(
        val stable: APIResponse<T>,
        val dev: APIResponse<T>
    )

    private data class EndpointSuccess<T>(
        val endpoint: Endpoint,
        val data: T
    )

    private suspend inline fun <reified T> queryBoth(
        query: String,
        variables: JsonObject?
    ): EndpointResponses<T> = coroutineScope {
        val stable = async { graphqlWithDeadline<T>(STABLE_ENDPOINT.graphqlUrl, query, variables) }
        val dev = async { graphqlWithDeadline<T>(DEV_ENDPOINT.graphqlUrl, query, variables) }
        EndpointResponses(stable.await(), dev.await())
    }

    private fun <T, R> selectFreshestResponse(
        responses: EndpointResponses<T>,
        timestamp: (T) -> String?,
        transform: (T, Endpoint) -> R,
        preferDev: ((dev: T, stable: T) -> Boolean)? = null
    ): APIResponse<R> {
        val stable = responses.stable
        val dev = responses.dev
        val selected = when {
            stable is APIResponse.Success && dev is APIResponse.Success -> {
                val devIsFresher = preferDev?.invoke(dev.data, stable.data)
                    ?: isNewer(timestamp(dev.data), timestamp(stable.data))
                if (devIsFresher) {
                    EndpointSuccess(DEV_ENDPOINT, dev.data)
                } else {
                    EndpointSuccess(STABLE_ENDPOINT, stable.data)
                }
            }
            stable is APIResponse.Success -> EndpointSuccess(STABLE_ENDPOINT, stable.data)
            dev is APIResponse.Success -> EndpointSuccess(DEV_ENDPOINT, dev.data)
            else -> return when (dev) {
                is APIResponse.Error -> APIResponse.Error(dev.error)
                is APIResponse.Failure -> APIResponse.Failure(dev.error)
                is APIResponse.Success -> APIResponse.Success(transform(dev.data, DEV_ENDPOINT))
            }
        }
        return APIResponse.Success(transform(selected.data, selected.endpoint))
    }

    private fun isDevDiscoveryResponseFresher(
        dev: BundlesQueryData,
        stable: BundlesQueryData
    ): Boolean {
        val devBundle = dev.bundle.firstOrNull()
        val stableBundle = stable.bundle.firstOrNull()
        when {
            devBundle != null && stableBundle == null -> return true
            devBundle == null -> return false
            isNewer(devBundle.createdAt, stableBundle?.createdAt) -> return true
            isNewer(stableBundle?.createdAt, devBundle.createdAt) -> return false
        }

        val devRefresh = dev.refreshJobs.firstOrNull()
        val stableRefresh = stable.refreshJobs.firstOrNull()
        return isNewer(
            newestTimestamp(devRefresh?.completedAt, devRefresh?.startedAt),
            newestTimestamp(stableRefresh?.completedAt, stableRefresh?.startedAt)
        )
    }

    private fun endpointForHost(host: String): Endpoint? = when {
        host.equals(STABLE_ENDPOINT.host, ignoreCase = true) -> STABLE_ENDPOINT
        host.equals(DEV_ENDPOINT.host, ignoreCase = true) -> DEV_ENDPOINT
        else -> null
    }

    private fun isNewer(candidate: String?, baseline: String?): Boolean {
        val candidateValue = candidate?.trim().takeIf { !it.isNullOrBlank() } ?: return false
        val baselineValue = baseline?.trim().takeIf { !it.isNullOrBlank() } ?: return true
        val candidateInstant = parseTimestamp(candidateValue)
        val baselineInstant = parseTimestamp(baselineValue)
        return if (candidateInstant != null && baselineInstant != null) {
            candidateInstant > baselineInstant
        } else {
            candidateValue > baselineValue
        }
    }

    private fun newestTimestamp(vararg values: String?): String? =
        values.mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .reduceOrNull { newest, candidate ->
                if (isNewer(candidate, newest)) candidate else newest
            }

    private fun parseTimestamp(raw: String): Instant? =
        runCatching { Instant.parse(raw) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(raw).toInstant(TimeZone.UTC) }.getOrNull()

    companion object {
        private val STABLE_ENDPOINT = Endpoint(
            host = ExternalBundlesEndpoints.STABLE_HOST,
            graphqlUrl = "https://${ExternalBundlesEndpoints.STABLE_HOST}/hasura/v1/graphql"
        )
        private val DEV_ENDPOINT = Endpoint(
            host = ExternalBundlesEndpoints.DEV_HOST,
            graphqlUrl = "https://${ExternalBundlesEndpoints.DEV_HOST}/hasura/v1/graphql"
        )
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

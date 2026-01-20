package app.revanced.manager.network.api

import app.revanced.manager.network.dto.BundleNode
import app.revanced.manager.network.dto.BundlesQueryData
import app.revanced.manager.network.dto.ExternalBundlePatch
import app.revanced.manager.network.dto.ExternalBundlePackage
import app.revanced.manager.network.dto.ExternalBundleSnapshot
import app.revanced.manager.network.dto.GraphqlError
import app.revanced.manager.network.dto.GraphqlRequest
import app.revanced.manager.network.dto.GraphqlResponse
import app.revanced.manager.network.dto.PatchNode
import app.revanced.manager.network.service.HttpService
import app.revanced.manager.network.utils.APIFailure
import app.revanced.manager.network.utils.APIResponse
import app.revanced.manager.network.utils.transform
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ExternalBundlesApi(
    private val client: HttpService,
) {
    suspend fun getBundles(
        packageNameQuery: String? = null,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0
    ): APIResponse<List<ExternalBundleSnapshot>> {
        val stableVariables = buildBundleVariables(packageNameQuery, limit, offset)
        val stableResponse = graphql<BundlesQueryData>(STABLE_GRAPHQL_URL, BUNDLES_QUERY, stableVariables)
        if (stableResponse is APIResponse.Success) {
            return stableResponse.transform { data ->
                data.bundle.map { it.toSnapshot(STABLE_BUNDLES_HOST) }
            }
        }

        val devVariables = buildBundleVariables(packageNameQuery, limit, offset)
        val devResponse = graphql<BundlesQueryData>(DEV_GRAPHQL_URL, BUNDLES_QUERY, devVariables)
        if (devResponse is APIResponse.Success) {
            return devResponse.transform { data ->
                data.bundle.map { it.toSnapshot(DEV_BUNDLES_HOST) }
            }
        }

        return when (devResponse) {
            is APIResponse.Error -> APIResponse.Error(devResponse.error)
            is APIResponse.Failure -> APIResponse.Failure(devResponse.error)
            is APIResponse.Success -> APIResponse.Success(emptyList())
        }
    }

    suspend fun getBundleById(bundleId: Int): APIResponse<ExternalBundleSnapshot?> {
        val variables = buildJsonObject {
            put("id", JsonPrimitive(bundleId))
        }
        val stableResponse = graphql<BundlesQueryData>(STABLE_GRAPHQL_URL, BUNDLE_BY_ID_QUERY, variables)
        if (stableResponse is APIResponse.Success) {
            return stableResponse.transform { data ->
                data.bundle.firstOrNull()?.toSnapshot(STABLE_BUNDLES_HOST)
            }
        }

        val devResponse = graphql<BundlesQueryData>(DEV_GRAPHQL_URL, BUNDLE_BY_ID_QUERY, variables)
        if (devResponse is APIResponse.Success) {
            return devResponse.transform { data ->
                data.bundle.firstOrNull()?.toSnapshot(DEV_BUNDLES_HOST)
            }
        }

        return when (devResponse) {
            is APIResponse.Error -> APIResponse.Error(devResponse.error)
            is APIResponse.Failure -> APIResponse.Failure(devResponse.error)
            is APIResponse.Success -> APIResponse.Success(null)
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
        val stableResponse = graphql<BundlesQueryData>(STABLE_GRAPHQL_URL, BUNDLE_LATEST_QUERY, variables)
        if (stableResponse is APIResponse.Success) {
            return stableResponse.transform { data ->
                data.bundle.firstOrNull()?.toSnapshot(STABLE_BUNDLES_HOST)
            }
        }

        val devResponse = graphql<BundlesQueryData>(DEV_GRAPHQL_URL, BUNDLE_LATEST_QUERY, variables)
        if (devResponse is APIResponse.Success) {
            return devResponse.transform { data ->
                data.bundle.firstOrNull()?.toSnapshot(DEV_BUNDLES_HOST)
            }
        }

        return when (devResponse) {
            is APIResponse.Error -> APIResponse.Error(devResponse.error)
            is APIResponse.Failure -> APIResponse.Failure(devResponse.error)
            is APIResponse.Success -> APIResponse.Success(null)
        }
    }

    suspend fun getBundlePatches(bundleId: Int): APIResponse<List<ExternalBundlePatch>> {
        val variables = buildJsonObject {
            put("id", JsonPrimitive(bundleId))
        }
        val stableResponse = graphql<BundlesQueryData>(STABLE_GRAPHQL_URL, BUNDLE_PATCHES_QUERY, variables)
        if (stableResponse is APIResponse.Success) {
            return stableResponse.transform { data ->
                val bundle = data.bundle.firstOrNull()
                bundle?.patches?.map { it.toPatch() }.orEmpty()
            }
        }

        val devResponse = graphql<BundlesQueryData>(DEV_GRAPHQL_URL, BUNDLE_PATCHES_QUERY, variables)
        if (devResponse is APIResponse.Success) {
            return devResponse.transform { data ->
                val bundle = data.bundle.firstOrNull()
                bundle?.patches?.map { it.toPatch() }.orEmpty()
            }
        }

        return when (devResponse) {
            is APIResponse.Error -> APIResponse.Error(devResponse.error)
            is APIResponse.Failure -> APIResponse.Failure(devResponse.error)
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
            ownerName = metadata?.ownerName.orEmpty(),
            ownerAvatarUrl = metadata?.ownerAvatarUrl,
            repoName = metadata?.repoName.orEmpty(),
            repoDescription = metadata?.repoDescription,
            sourceUrl = source?.url.orEmpty(),
            repoStars = metadata?.repoStars ?: 0,
            repoPushedAt = metadata?.repoPushedAt,
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

    companion object {
        private const val DEV_GRAPHQL_URL = "https://revanced-external-bundles-dev.brosssh.com/hasura/v1/graphql"
        private const val STABLE_GRAPHQL_URL = "https://revanced-external-bundles.brosssh.com/hasura/v1/graphql"
        private const val DEV_BUNDLES_HOST = "revanced-external-bundles-dev.brosssh.com"
        private const val STABLE_BUNDLES_HOST = "revanced-external-bundles.brosssh.com"
        private const val BUNDLES_QUERY = """
            query BundleDiscovery(${"$"}where: bundle_bool_exp, ${"$"}limit: Int, ${"$"}offset: Int) {
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
        private const val DEFAULT_PAGE_SIZE = 30
    }

    private class GraphqlException(message: String) : Exception(message)
}

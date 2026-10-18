package app.urv.manager.network.api

internal object CatalogHistoryQueries {
    const val RELEASES = """
        query ReleaseHistory(${"$"}source: String!, ${"$"}after: String, ${"$"}limit: Int!) {
          historical_releases(source: ${"$"}source, after: ${"$"}after, limit: ${"$"}limit, order: "desc") {
            generation next_cursor
            data { id name tag version is_prerelease published_at release_url availability
              bundle_ids bundle_types metadata_document_url }
          }
        }
    """
    const val RELEASE = """
        query HistoricalRelease(${"$"}id: String!) {
          catalog_generation
          historical_release(id: ${"$"}id) {
            id name tag version is_prerelease published_at release_url availability
            bundle_ids bundle_types changelog release_notes metadata_document_url
          }
        }
    """
    const val DOCUMENT = """
        query HistoricalDocument(${"$"}id: String!, ${"$"}offset: Int!) {
          catalog_generation
          historical_release(id: ${"$"}id) {
            document(offset: ${"$"}offset, limit: 65536) { encoding data total_bytes next_offset }
          }
        }
    """
    const val BUNDLES = """
        query HistoricalBundles(${"$"}where: bundle_bool_exp!, ${"$"}generation: String!) {
          catalog_generation
          historical_bundles(where: ${"$"}where, generation: ${"$"}generation, limit: 100, order_by: {id: asc}) {
            id bundle_type created_at download_url signature_download_url file_hash
            need_patches_update patcher_failure_fingerprint metadata_status
            is_prerelease version icon_url owner_avatar_url patch_count
            source { url source_metadatum { owner_name owner_avatar_url bundle_icon_url
              repo_name repo_description repo_stars repo_pushed_at is_repo_archived } }
          }
        }
    """
    const val PATCHES = """
        query HistoricalPatches(${"$"}id: Int!, ${"$"}offset: Int, ${"$"}limit: Int!, ${"$"}generation: String!) {
          patch: historical_patches(generation: ${"$"}generation,
            where: {bundle_fk: {_eq: ${"$"}id}, metadata_status: {_eq: "verified"}},
            limit: ${"$"}limit, offset: ${"$"}offset, order_by: {id: asc}) {
            id name description
            patch_packages(limit: 100) {
              package { name version app_name icon_url }
            }
          }
        }
    """
    const val LINKS = """
        query HistoricalPatchLinks(${"$"}id: Int!, ${"$"}offset: Int, ${"$"}generation: String!) {
          links: historical_patch_packages(generation: ${"$"}generation,
            where: {patch_fk: {_eq: ${"$"}id}}, limit: 100, offset: ${"$"}offset) {
            package { name version app_name icon_url }
          }
        }
    """
}

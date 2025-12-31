package app.revanced.manager.network.api

import app.revanced.manager.network.dto.ExternalBundleSnapshot
import app.revanced.manager.network.service.HttpService
import app.revanced.manager.network.utils.APIResponse
import io.ktor.client.request.url

class ExternalBundlesApi(
    private val client: HttpService,
) {
    suspend fun getSnapshot(): APIResponse<List<ExternalBundleSnapshot>> = client.request {
        url("$BASE_URL/snapshot")
    }

    companion object {
        private const val BASE_URL = "https://revanced-external-bundles.brosssh.com"
    }
}

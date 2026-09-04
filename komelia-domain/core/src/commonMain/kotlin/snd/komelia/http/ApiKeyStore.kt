package snd.komelia.http

import io.ktor.http.*
import kotlinx.coroutines.flow.StateFlow
import snd.komelia.settings.SecretsRepository

class ApiKeyStore(
    private val komgaUrl: StateFlow<Url>,
    private val secretsRepository: SecretsRepository,
) {
    var apiKey: String? = null
        private set

    suspend fun loadStoredApiKey() {
        val url = komgaUrl.value
        apiKey = secretsRepository.getApiKey(url.toString())
    }

    suspend fun setApiKey(url: String, apiKey: String) {
        secretsRepository.setApiKey(url, apiKey)
        this.apiKey = apiKey
    }

    suspend fun deleteApiKey(url: String) {
        secretsRepository.deleteApiKey(url)
        this.apiKey = null
    }
}
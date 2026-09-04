package snd.komelia.ui.login

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.snd_r.komelia.ui.komelia_ui.generated.resources.Res
import io.github.snd_r.komelia.ui.komelia_ui.generated.resources.login_cancel_error
import io.github.snd_r.komelia.ui.komelia_ui.generated.resources.login_error
import io.github.snd_r.komelia.ui.komelia_ui.generated.resources.login_invalid_credentials
import io.github.snd_r.komelia.ui.komelia_ui.generated.resources.login_unexpected_response
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.http.HttpStatusCode.Companion.Unauthorized
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import snd.komelia.AppNotifications
import snd.komelia.KomgaAuthenticationState
import snd.komelia.http.ApiKeyStore
import snd.komelia.komga.api.KomgaLibraryApi
import snd.komelia.komga.api.KomgaUserApi
import snd.komelia.offline.api.OfflineLibraryApi
import snd.komelia.offline.server.repository.OfflineMediaServerRepository
import snd.komelia.offline.settings.OfflineSettingsRepository
import snd.komelia.offline.user.model.OfflineUser
import snd.komelia.offline.user.repository.OfflineUserRepository
import snd.komelia.settings.CommonSettingsRepository
import snd.komelia.settings.SecretsRepository
import snd.komelia.ui.LoadState
import snd.komelia.ui.LoadState.Uninitialized
import snd.komelia.ui.platform.PlatformType
import snd.komelia.ui.platform.PlatformType.DESKTOP
import snd.komelia.ui.platform.PlatformType.MOBILE
import snd.komelia.ui.platform.PlatformType.WEB_KOMF

private val logger = KotlinLogging.logger { }

class LoginViewModel(
    private val settingsRepository: CommonSettingsRepository,
    private val secretsRepository: SecretsRepository,
    private val apiKeyStore: ApiKeyStore,
    private val komgaUserApi: Flow<KomgaUserApi>,
    private val komgaLibraryApi: Flow<KomgaLibraryApi>,
    private val komgaAuthState: KomgaAuthenticationState,
    private val notifications: AppNotifications,
    private val platform: PlatformType,

    private val offlineUserRepository: OfflineUserRepository?,
    private val offlineServerRepository: OfflineMediaServerRepository?,
    private val offlineSettingsRepository: OfflineSettingsRepository?,
    private val offlineLibraryApi: OfflineLibraryApi?,
) : StateScreenModel<LoadState<Unit>>(Uninitialized) {

    var url = MutableStateFlow("")
    var user = MutableStateFlow("")
    var password = MutableStateFlow("")
    var apiKey = MutableStateFlow("")
    var userLoginError = MutableStateFlow<String?>(null)
    var autoLoginError = MutableStateFlow<String?>(null)
    val offlineIsAvailable = MutableStateFlow(false)
    private val offlineUser = MutableStateFlow<OfflineUser?>(null)
    val canGoOfflineAsCurrentUser = offlineUser.map { it != null }

    private val hasLanPermission = MutableStateFlow(false)

    fun initialize(hasLanPermission: Boolean) {
        this.hasLanPermission.value = hasLanPermission

        if (state.value !is Uninitialized) return

        screenModelScope.launch {
            url.value = settingsRepository.getServerUrl().first()
            user.value = settingsRepository.getCurrentUser().first()
            val cookie = secretsRepository.getCookie(url.value)
            if (cookie == null) {
                user.value = ""
                url.value = ""
            }

            val offlineUsers = offlineUserRepository?.findAll() ?: emptyList()
            val offlineServer = offlineServerRepository?.findByUrl(url.value)
            offlineIsAvailable.value = offlineUsers.any { it.id != OfflineUser.ROOT }
            offlineUser.value = offlineServer?.let { server -> offlineUsers.firstOrNull { it.serverId == server.id } }
            val isOffline = offlineSettingsRepository?.getOfflineMode()?.first() ?: false

            when (platform) {
                MOBILE, DESKTOP -> {
                    if (isOffline || cookie != null) {
                        mutableState.value = LoadState.Loading
                        tryAutologin()
                    } else {
                        mutableState.value = LoadState.Error(RuntimeException("Not logged in"))
                    }
                }

                WEB_KOMF -> {
                    if(apiKeyStore.apiKey!=null) {
                        mutableState.value = LoadState.Loading
                        tryAutologin()
                    }else{
                        mutableState.value = LoadState.Error(RuntimeException("Not logged in"))
                    }
                }
            }
        }
    }

    fun retryAutoLogin() {
        screenModelScope.launch {
            mutableState.value = LoadState.Loading
            tryAutologin()
        }
    }

    fun cancel() {
        screenModelScope.coroutineContext.cancelChildren()
        screenModelScope.launch {
            val message = getString(Res.string.login_cancel_error)
            mutableState.value = LoadState.Error(RuntimeException(message))
            userLoginError.value = message
        }
    }

    fun loginWithCredentials() {
        screenModelScope.launch {
            userLoginError.value = null
            settingsRepository.putServerUrl(url.value)
            settingsRepository.putCurrentUser(user.value)
            tryUserLogin(user.value, password.value)
        }
    }

    fun loginWithApiKey() {
        screenModelScope.launch {
            userLoginError.value = null
            settingsRepository.putServerUrl(url.value)
            apiKeyStore.setApiKey(url.value, apiKey.value)
            tryUserLogin(null, null)
        }
    }

    fun offlineLogin() {
        notifications.runCatchingToNotifications(screenModelScope) {
            val user = offlineUser.value ?: return@runCatchingToNotifications

            checkNotNull(offlineSettingsRepository).putOfflineMode(true)
            offlineSettingsRepository.putUserId(user.id)
            komgaAuthState.setStateValues(user.toKomgaUser(), checkNotNull(offlineLibraryApi).getLibraries())
            mutableState.value = LoadState.Success(Unit)
        }
    }

    private suspend fun tryAutologin() {
        try {
            tryLogin()
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            mutableState.value = LoadState.Error(e)
            autoLoginError.value = when (e) {
                is NoTransformationFoundException -> getString(Res.string.login_unexpected_response, url)
                is ClientRequestException -> {
                    if (e.response.status == Unauthorized) null
                    else getString(Res.string.login_error, "${e::class.simpleName} ${e.message}")
                }

                else -> getString(Res.string.login_error, "${e::class.simpleName} ${e.message}")
            }
        }
    }

    private suspend fun tryUserLogin(username: String?, password: String?) {
        try {
            tryLogin(username, password)
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            mutableState.value = LoadState.Error(e)
            userLoginError.value = when (e) {
                is NoTransformationFoundException -> getString(Res.string.login_unexpected_response, url)
                is ClientRequestException -> {
                    if (e.response.status == Unauthorized) getString(Res.string.login_invalid_credentials)
                    else getString(Res.string.login_error, "${e::class.simpleName} ${e.message}")
                }

                else -> getString(Res.string.login_error, "${e::class.simpleName} ${e.message}")
            }
        }
    }

    private suspend fun tryLogin(
        username: String? = null,
        password: String? = null
    ) {
        val userApi = this.komgaUserApi.first()
        val libraryApi = this.komgaLibraryApi.first()
        val user =
            if (username != null && password != null) userApi.getMe(username, password, true)
            else userApi.getMe()

        val libraries = libraryApi.getLibraries()
        komgaAuthState.setStateValues(user, libraries)
        mutableState.value = LoadState.Success(Unit)
    }
}

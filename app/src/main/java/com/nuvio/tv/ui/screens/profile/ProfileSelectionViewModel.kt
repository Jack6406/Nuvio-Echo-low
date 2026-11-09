package com.nuvio.tv.ui.screens.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.ProfileSettingsSyncService
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.core.sync.SetProfilePinResult
import com.nuvio.tv.data.local.ProfileLockStateDataStore
import com.nuvio.tv.data.local.CustomAvatarDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import com.nuvio.tv.data.remote.supabase.ProfileBackgroundCatalogItem
import com.nuvio.tv.data.remote.supabase.ProfileBackgroundRepository
import com.nuvio.tv.data.remote.supabase.SupabaseProfilePinVerifyResult
import com.nuvio.tv.data.repository.MemberAccessRepository
import com.nuvio.tv.domain.model.CosmeticEntitlement
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.domain.repository.MetaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CreateProfileResult {
    data class Created(
        val profile: UserProfile,
        val settingsCopyResult: Result<Unit>?
    ) : CreateProfileResult

    data object Failed : CreateProfileResult
}

@HiltViewModel
class ProfileSelectionViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val profileSyncService: ProfileSyncService,
    private val profileSettingsSyncService: ProfileSettingsSyncService,
    private val avatarRepository: AvatarRepository,
    private val profileBackgroundRepository: ProfileBackgroundRepository,
    memberAccessRepository: MemberAccessRepository,
    private val profileLockStateDataStore: ProfileLockStateDataStore,
    private val themeDataStore: com.nuvio.tv.data.local.ThemeDataStore,
    private val customAvatarDataStore: CustomAvatarDataStore,
    private val binsRepository: com.nuvio.tv.core.bins.BinsRepository,
    private val libraryRepository: com.nuvio.tv.domain.repository.LibraryRepository,
    private val watchProgressRepository: com.nuvio.tv.domain.repository.WatchProgressRepository,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val metaRepository: MetaRepository
) : ViewModel() {

    private val posterWallSeed = MutableStateFlow(System.nanoTime())
    private val focusedProfileIdFlow = MutableStateFlow<Int?>(null)
    private val fetchedMetaBackdrops = MutableStateFlow<Map<String, String>>(emptyMap())

    fun refreshPosterWall() {
        posterWallSeed.value = System.nanoTime()
    }

    fun setFocusedProfileId(profileId: Int?) {
        focusedProfileIdFlow.value = profileId
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val focusedProfileProgressFlow = combine(
        focusedProfileIdFlow,
        profileManager.activeProfileId
    ) { focused, active ->
        focused ?: active
    }.distinctUntilChanged().flatMapLatest { pid ->
        watchProgressPreferences.observeAllProgress(pid)
    }

    val backdropPosters: StateFlow<List<String>> = combine(
        focusedProfileProgressFlow,
        watchProgressRepository.allProgress,
        watchProgressRepository.continueWatching,
        com.nuvio.tv.data.local.CustomBackdropStore.reactiveOverrides,
        combine(libraryRepository.libraryItems, fetchedMetaBackdrops) { lib, fetched -> lib to fetched }
    ) { profileProgressList, allProgressList, cwList, customOverrides, (libraryItems, fetchedMap) ->
        val imdbRegex = Regex("(tt\\d{5,12})")
        fun resolveBackdropUrl(
            backdropUrl: String?,
            posterUrl: String?,
            rawId: String?,
            videoId: String?,
            contentType: String?
        ): String? {
            val cleanRawId = rawId?.trim()?.takeIf { it.isNotBlank() }
            val baseId = cleanRawId?.substringBefore(':')?.trim()?.takeIf { it.isNotBlank() }
            if (baseId != null) {
                customOverrides[baseId]?.takeIf { it.isNotBlank() }?.let { return it }
            }
            if (cleanRawId != null) {
                customOverrides[cleanRawId]?.takeIf { it.isNotBlank() }?.let { return it }
            }
            val cleanBackdrop = backdropUrl?.trim()?.takeIf { it.isNotBlank() && !it.contains("/poster/") }
            if (cleanBackdrop != null) return cleanBackdrop

            if (baseId != null) {
                fetchedMap[baseId]?.takeIf { it.isNotBlank() }?.let { return it }
                val cachedType = contentType?.takeIf { it.isNotBlank() } ?: "movie"
                val cached = metaRepository.getCachedMeta(cachedType, baseId)?.backdropUrl
                    ?: metaRepository.getCachedMeta("series", baseId)?.backdropUrl
                    ?: metaRepository.getCachedMeta("movie", baseId)?.backdropUrl
                if (!cached.isNullOrBlank() && !cached.contains("/poster/")) {
                    return cached
                }
                libraryItems.firstOrNull { it.id == baseId || it.id == cleanRawId }
                    ?.background
                    ?.takeIf { it.isNotBlank() && !it.contains("/poster/") }
                    ?.let { return it }
            }

            val imdb = baseId?.let { imdbRegex.find(it)?.groupValues?.get(1) }
                ?: cleanRawId?.let { imdbRegex.find(it)?.groupValues?.get(1) }
                ?: videoId?.let { imdbRegex.find(it)?.groupValues?.get(1) }
            if (imdb != null) {
                return "https://images.metahub.space/background/large/$imdb/img"
            }

            if (baseId != null && !fetchedMap.containsKey(baseId)) {
                val lookupType = contentType?.takeIf { it.isNotBlank() } ?: "movie"
                viewModelScope.launch {
                    runCatching {
                        val res = metaRepository.getMetaFromAllAddons(type = lookupType, id = baseId)
                            .firstOrNull { it !is com.nuvio.tv.core.network.NetworkResult.Loading }
                        val meta = (res as? com.nuvio.tv.core.network.NetworkResult.Success)?.data
                        val resolved = meta?.backdropUrl?.takeIf { it.isNotBlank() && !it.contains("/poster/") }
                        if (!resolved.isNullOrBlank()) {
                            fetchedMetaBackdrops.value = fetchedMetaBackdrops.value + (baseId to resolved)
                        }
                    }
                }
            }

            return posterUrl?.trim()?.takeIf { it.isNotBlank() }
        }

        // Combine all sources of progress and sort strictly by lastWatched descending so the true
        // most recently watched item (whether mid-playback or completed for Next Up) always wins.
        val mergedProgress = (profileProgressList + allProgressList + cwList)
            .sortedByDescending { it.lastWatched }

        val latestProgressBackdrop = mergedProgress.firstNotNullOfOrNull { wp ->
            resolveBackdropUrl(
                backdropUrl = wp.backdrop,
                posterUrl = wp.poster,
                rawId = wp.contentId.ifBlank { wp.videoId },
                videoId = wp.videoId,
                contentType = wp.contentType
            )
        }

        val primaryBackdrop = latestProgressBackdrop
            ?: libraryItems.firstNotNullOfOrNull { item ->
                resolveBackdropUrl(
                    backdropUrl = item.background,
                    posterUrl = item.poster,
                    rawId = item.id,
                    videoId = item.id,
                    contentType = item.type
                )
            }
            ?: "https://images.metahub.space/background/large/tt15239678/img"

        listOf(primaryBackdrop)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        listOf("https://images.metahub.space/background/large/tt15239678/img")
    )
    val activeProfileId: StateFlow<Int> = profileManager.activeProfileId
    val profiles: StateFlow<List<UserProfile>> = profileManager.profiles

    val canAddProfile: Boolean
        get() = profileManager.canCreateProfile

    private val _avatarCatalog = MutableStateFlow<List<AvatarCatalogItem>>(emptyList())
    val avatarCatalog: StateFlow<List<AvatarCatalogItem>> = _avatarCatalog.asStateFlow()
    private var defaultAvatarCatalog: List<AvatarCatalogItem> = emptyList()

    val hasProfileAvatarAccess: StateFlow<Boolean> = memberAccessRepository.access
        .map { access -> access.entitlements.includes(CosmeticEntitlement.PROFILE_AVATARS) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val hasProfileBackgroundAccess: StateFlow<Boolean> = memberAccessRepository.access
        .map { access -> access.entitlements.includes(CosmeticEntitlement.PROFILE_BACKGROUNDS) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val profileBackgroundCatalog: StateFlow<List<ProfileBackgroundCatalogItem>> = combine(
        profileBackgroundRepository.catalog,
        hasProfileBackgroundAccess
    ) { catalog, hasAccess -> if (hasAccess) catalog else emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _isCopyingSettings = MutableStateFlow(false)
    val isCopyingSettings: StateFlow<Boolean> = _isCopyingSettings.asStateFlow()

    val profilePinEnabled: StateFlow<Map<Int, Boolean>> = profileLockStateDataStore.pinEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _isPinOperationInProgress = MutableStateFlow(false)
    val isPinOperationInProgress: StateFlow<Boolean> = _isPinOperationInProgress.asStateFlow()

    init {
        refreshProfilePinStates()
        viewModelScope.launch {
            customAvatarDataStore.avatars.collectLatest { customAvatars ->
                _avatarCatalog.value = defaultAvatarCatalog + customAvatars
            }
        }
        viewModelScope.launch {
            hasProfileAvatarAccess.collectLatest { hasAccess ->
                loadAvatarCatalog(hasAccess)
            }
        }
        viewModelScope.launch {
            hasProfileBackgroundAccess.collectLatest { hasAccess ->
                if (hasAccess) {
                    profileBackgroundRepository.ensureLoaded()
                } else {
                    profileBackgroundRepository.invalidateCache()
                }
            }
        }
        viewModelScope.launch {
            profiles.flatMapLatest { profileList ->
                if (profileList.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(
                        profileList.map { profile ->
                            themeDataStore.observeThemeForProfile(profile.id)
                                .map { theme -> profile.id to (theme ?: com.nuvio.tv.domain.model.AppTheme.WHITE) }
                        }
                    ) { entries -> entries.toMap() }
                }
            }.collectLatest { themes ->
                _profileThemes.value = themes
            }
        }
    }

    private val _profileThemes = MutableStateFlow<Map<Int, com.nuvio.tv.domain.model.AppTheme>>(emptyMap())
    val profileThemes: StateFlow<Map<Int, com.nuvio.tv.domain.model.AppTheme>> = _profileThemes.asStateFlow()

    fun loadAvatarCatalog() {
        viewModelScope.launch {
            loadAvatarCatalog(hasProfileAvatarAccess.value)
        }
    }

    private suspend fun loadAvatarCatalog(hasMemberAccess: Boolean) {
        try {
            defaultAvatarCatalog = avatarRepository.getAvatarCatalog(hasMemberAccess)
            _avatarCatalog.value = defaultAvatarCatalog + customAvatarDataStore.avatars.first()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e("ProfileSelectionVM", "Failed to load avatar catalog", error)
        }
    }

    fun getAvatarImageUrl(avatarId: String?): String? {
        if (avatarId == null) return null
        return avatarRepository.getAvatarImageUrl(avatarId, _avatarCatalog.value)
    }

    fun loadProfileBackground(id: String) {
        if (hasProfileBackgroundAccess.value) profileBackgroundRepository.loadSelectedAndPreload(id)
    }

    fun preloadProfileBackgrounds() {
        if (hasProfileBackgroundAccess.value) profileBackgroundRepository.preloadImages()
    }

    var isSelectingProfile = false
        private set

    fun selectProfile(id: Int, onComplete: () -> Unit, onFailure: () -> Unit) {
        if (isSelectingProfile) return
        isSelectingProfile = true
        viewModelScope.launch {
            try {
                profileManager.setActiveProfile(id)
                onComplete()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("ProfileSelectionVM", "Failed to select profile", error)
                onFailure()
            } finally {
                isSelectingProfile = false
            }
        }
    }

    fun createProfile(
        name: String,
        avatarColorHex: String,
        avatarId: String? = null,
        copyFromProfileId: Int? = null,
        copyProviderCredentials: Boolean = false,
        onComplete: (CreateProfileResult) -> Unit = {}
    ) {
        if (_isCreating.value) return
        viewModelScope.launch {
            _isCreating.value = true
            val result = try {
                val profile = profileManager.createProfile(
                    name = name,
                    avatarColorHex = avatarColorHex,
                    avatarId = avatarId
                )
                if (profile != null) {
                    profileSyncService.pushToRemote()
                    val copyResult = copyFromProfileId?.let { sourceProfileId ->
                        profileSettingsSyncService.copyProfileSetup(
                            sourceProfileId = sourceProfileId,
                            targetProfileId = profile.id,
                            copyProviderCredentials = copyProviderCredentials
                        )
                    }
                    refreshProfilePinStates()
                    CreateProfileResult.Created(profile, copyResult)
                } else {
                    CreateProfileResult.Failed
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("ProfileSelectionVM", "Failed to create profile", error)
                CreateProfileResult.Failed
            } finally {
                _isCreating.value = false
            }
            onComplete(result)
        }
    }

    fun copyProfileSettings(
        sourceProfileId: Int,
        targetProfileId: Int,
        copyProviderCredentials: Boolean,
        onComplete: (Result<Unit>) -> Unit
    ) {
        if (_isCopyingSettings.value) return
        viewModelScope.launch {
            _isCopyingSettings.value = true
            val result = try {
                profileSettingsSyncService.copyProfileSetup(
                    sourceProfileId = sourceProfileId,
                    targetProfileId = targetProfileId,
                    copyProviderCredentials = copyProviderCredentials
                )
            } finally {
                _isCopyingSettings.value = false
            }
            onComplete(result)
        }
    }

    fun updateProfile(profile: UserProfile) {
        if (_isSaving.value) return
        viewModelScope.launch {
            _isSaving.value = true
            profileManager.updateProfile(profile)
            profileSyncService.pushToRemote()
            refreshProfilePinStates()
            _isSaving.value = false
        }
    }

    fun deleteProfile(id: Int) {
        viewModelScope.launch {
            profileManager.deleteProfile(id)
            profileSyncService.deleteProfileData(id)
            profileSyncService.pushToRemote()
            refreshProfilePinStates()
        }
    }

    fun refreshProfilePinStates() {
        viewModelScope.launch {
            var attempt = 0
            while (attempt < 4) {
                val result = profileSyncService.pullProfileLockStates()
                if (result.isSuccess) {
                    profileLockStateDataStore.replaceAll(result.getOrNull().orEmpty())
                    return@launch
                }
                Log.e(
                    "ProfileSelectionVM",
                    "Failed to refresh profile PIN states (attempt=$attempt)",
                    result.exceptionOrNull()
                )
                attempt++
                delay(2000L * attempt)
            }
        }
    }

    fun isProfilePinEnabled(profileId: Int): Boolean {
        return profilePinEnabled.value[profileId] == true
    }

    fun setProfilePin(
        profileId: Int,
        pin: String,
        currentPin: String? = null,
        onComplete: (SetProfilePinResult) -> Unit
    ) {
        if (_isPinOperationInProgress.value) return
        viewModelScope.launch {
            _isPinOperationInProgress.value = true
            val result = profileSyncService.setProfilePin(profileId, pin, currentPin)
            if (result is SetProfilePinResult.Success || result is SetProfilePinResult.CurrentPinRequired) {
                profileLockStateDataStore.setPinEnabled(profileId, true)
            }
            _isPinOperationInProgress.value = false
            onComplete(result)
        }
    }

    fun clearProfilePin(profileId: Int, currentPin: String? = null, onComplete: (Boolean) -> Unit) {
        if (_isPinOperationInProgress.value) return
        viewModelScope.launch {
            _isPinOperationInProgress.value = true
            val success = profileSyncService.clearProfilePin(profileId, currentPin).isSuccess
            if (success) {
                profileLockStateDataStore.setPinEnabled(profileId, false)
            }
            _isPinOperationInProgress.value = false
            onComplete(success)
        }
    }

    fun verifyProfilePin(profileId: Int, pin: String, onComplete: (Result<SupabaseProfilePinVerifyResult>) -> Unit) {
        if (_isPinOperationInProgress.value) return
        viewModelScope.launch {
            _isPinOperationInProgress.value = true
            val result = profileSyncService.verifyProfilePin(profileId, pin)
            _isPinOperationInProgress.value = false
            onComplete(result)
        }
    }
}

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
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import com.nuvio.tv.data.remote.supabase.ProfileBackgroundCatalogItem
import com.nuvio.tv.data.remote.supabase.ProfileBackgroundRepository
import com.nuvio.tv.data.remote.supabase.SupabaseProfilePinVerifyResult
import com.nuvio.tv.data.repository.MemberAccessRepository
import com.nuvio.tv.domain.model.CosmeticEntitlement
import com.nuvio.tv.domain.model.UserProfile
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
    private val watchProgressRepository: com.nuvio.tv.domain.repository.WatchProgressRepository
) : ViewModel() {

    private val posterWallSeed = MutableStateFlow(System.nanoTime())

    fun refreshPosterWall() {
        posterWallSeed.value = System.nanoTime()
    }

    val backdropPosters: StateFlow<List<String>> = combine(
        libraryRepository.libraryItems,
        binsRepository.state,
        watchProgressRepository.allProgress,
        posterWallSeed
    ) { libraryItems, binsState, progressList, seed ->
        val imdbRegex = Regex("(tt\\d{5,12})")
        fun posterOrMetahub(posterUrl: String?, id: String?): String? {
            val clean = posterUrl?.trim()?.takeIf { it.isNotBlank() && !it.contains("/background/") }
            if (clean != null) return clean
            val imdb = id?.let { imdbRegex.find(it)?.groupValues?.get(1) }
            return imdb?.let { "https://images.metahub.space/poster/medium/$it/img" }
        }
        val rng = kotlin.random.Random(seed)
        val userPosters = buildList {
            libraryItems.forEach { item ->
                posterOrMetahub(item.poster, item.id)?.let(::add)
            }
            binsState.bins.forEach { bin ->
                bin.entries.forEach { entry ->
                    posterOrMetahub(entry.poster, entry.id)?.let(::add)
                }
            }
            progressList.forEach { wp ->
                posterOrMetahub(wp.poster, wp.contentId)?.let(::add)
            }
        }.distinct().shuffled(rng)
        val fallback = DEFAULT_PORTRAIT_WALL_POSTERS.shuffled(rng)
        (userPosters + fallback).distinct().take(28)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_PORTRAIT_WALL_POSTERS.shuffled())
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
            // Server reporting CurrentPinRequired means a PIN exists remotely —
            // reconcile local cache so we never forget it again.
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

private val DEFAULT_PORTRAIT_WALL_POSTERS = listOf(
    "https://images.metahub.space/poster/medium/tt0944947/img", // Game of Thrones
    "https://images.metahub.space/poster/medium/tt0903747/img", // Breaking Bad
    "https://images.metahub.space/poster/medium/tt4574334/img", // Stranger Things
    "https://images.metahub.space/poster/medium/tt15239678/img", // Dune: Part Two
    "https://images.metahub.space/poster/medium/tt15398776/img", // Oppenheimer
    "https://images.metahub.space/poster/medium/tt0816692/img", // Interstellar
    "https://images.metahub.space/poster/medium/tt11198330/img", // House of the Dragon
    "https://images.metahub.space/poster/medium/tt3581920/img", // The Last of Us
    "https://images.metahub.space/poster/medium/tt1375666/img", // Inception
    "https://images.metahub.space/poster/medium/tt0468569/img", // The Dark Knight
    "https://images.metahub.space/poster/medium/tt1877830/img", // The Batman
    "https://images.metahub.space/poster/medium/tt1190634/img", // The Boys
    "https://images.metahub.space/poster/medium/tt7366338/img", // Chernobyl
    "https://images.metahub.space/poster/medium/tt11280740/img", // Severance
    "https://images.metahub.space/poster/medium/tt14452776/img", // The Bear
    "https://images.metahub.space/poster/medium/tt2788316/img", // Shogun
    "https://images.metahub.space/poster/medium/tt13622776/img", // Fallout
    "https://images.metahub.space/poster/medium/tt9362722/img", // Spider-Man: Across the Spider-Verse
    "https://images.metahub.space/poster/medium/tt4154796/img", // Avengers: Endgame
    "https://images.metahub.space/poster/medium/tt1745960/img", // Top Gun: Maverick
    "https://images.metahub.space/poster/medium/tt6751668/img", // Parasite
    "https://images.metahub.space/poster/medium/tt0133093/img", // The Matrix
    "https://images.metahub.space/poster/medium/tt1856101/img", // Blade Runner 2049
    "https://images.metahub.space/poster/medium/tt8388390/img", // Chucky
    "https://images.metahub.space/poster/medium/tt7529770/img", // Miracle Workers
    "https://images.metahub.space/poster/medium/tt8633478/img", // Run
    "https://images.metahub.space/poster/medium/tt3032476/img", // Better Call Saul
    "https://images.metahub.space/poster/medium/tt2861424/img"  // Rick and Morty
)

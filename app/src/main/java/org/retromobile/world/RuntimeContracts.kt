package org.retromobile.world

/** Backend boundary. Engine-specific APIs must stay behind this contract. */
enum class BackendCapability {
    IMPORT_JAR_JAD,
    IMPORT_SIS_SISX,
    RENDER_TO_HOST_SURFACE,
    EXTERNAL_WINDOW_LAUNCH,
    VIRTUAL_KEY_INPUT,
    PER_APP_STORAGE,
    NETWORK_TOGGLE
}

data class LocalPackage(
    val packageId: String,
    val filePath: String,
    val fileName: String,
    val sha256: String,
    val runtime: RuntimeFamily
)

data class SystemImageReference(
    val displayName: String,
    val userSelectedPath: String,
    val family: String
)

sealed interface BackendResult {
    data object Success : BackendResult
    data class Unsupported(val reason: String) : BackendResult
    data class Failure(val reason: String, val recoverable: Boolean = false) : BackendResult
}

interface EmulatorBackend {
    val id: String
    val family: RuntimeFamily
    val capabilities: Set<BackendCapability>

    fun prepare(
        profile: DeviceProfile,
        systemImage: SystemImageReference? = null,
        callback: (BackendResult) -> Unit
    )

    fun install(packageFile: LocalPackage, callback: (BackendResult) -> Unit)
    fun launch(packageId: String, callback: (BackendResult) -> Unit)
    fun stop(callback: (BackendResult) -> Unit)
    fun sendKey(key: CanonicalKey, pressed: Boolean)
}

package com.micronizationlab.glafira

/**
 * Optional speech layer. It never owns task routing, tools or permissions; GLAFIRA Core does.
 * No plugin is activated by default and microphone capture is only started by an explicit UI action.
 */
enum class VoiceCapability {
    SPEECH_INPUT,
    SPEECH_OUTPUT
}

enum class VoicePluginAvailability {
    AVAILABLE,
    NEEDS_CONFIGURATION,
    UNAVAILABLE
}

data class VoicePluginDescriptor(
    val id: String,
    val displayName: String,
    val capabilities: Set<VoiceCapability>
) {
    init {
        require(id.isNotBlank()) { "Идентификатор голосового модуля не может быть пустым." }
        require(displayName.isNotBlank()) { "Название голосового модуля не может быть пустым." }
        require(capabilities.isNotEmpty()) { "Голосовой модуль должен объявить хотя бы одну возможность." }
    }
}

enum class VoiceStyleProfile {
    /** Original informal Russian delivery; not an imitation of an identifiable performer. */
    PERSONAL_INFORMAL_ORIGINAL,
    /** Formal, precise Russian delivery for work conversations. */
    WORK_FORMAL_ORIGINAL
}

data class VoiceAudio(
    val bytes: ByteArray,
    val mimeType: String,
    val providerProvenance: String
) {
    init {
        require(bytes.isNotEmpty()) { "Голосовой модуль вернул пустой аудиофайл." }
        require(mimeType.isNotBlank()) { "Не указан MIME-тип аудио." }
        require(providerProvenance.isNotBlank()) { "Не указано происхождение сгенерированного аудио." }
    }
}

/**
 * Provider adapter contract. Implementations must not log/store raw audio or credentials.
 * Provider secrets are resolved outside this interface by the secure credential broker.
 */
interface VoicePlugin {
    val descriptor: VoicePluginDescriptor
    fun availability(): VoicePluginAvailability
    fun transcribe(audio: ByteArray, mimeType: String): String
    fun synthesize(text: String, profile: VoiceStyleProfile): VoiceAudio
}

data class VoiceAssistantSettings(
    val enabled: Boolean = false,
    val selectedPluginId: String? = null
)

class VoicePluginRegistry(plugins: List<VoicePlugin>) {
    private val byId: Map<String, VoicePlugin>

    init {
        val entries = plugins.map { it.descriptor.id to it }
        require(entries.map { it.first }.distinct().size == entries.size) {
            "Идентификаторы голосовых модулей должны быть уникальными."
        }
        byId = entries.toMap()
    }

    fun find(pluginId: String?): VoicePlugin? = pluginId?.let(byId::get)
    fun all(): List<VoicePlugin> = byId.values.sortedBy { it.descriptor.displayName }
}

/**
 * Runtime gate. The caller persists [settings] in owner-controlled app preferences.
 * A disabled plugin is never returned to the UI for capture or synthesis.
 */
class VoiceAssistantRuntime(
    private val registry: VoicePluginRegistry,
    initialSettings: VoiceAssistantSettings = VoiceAssistantSettings()
) {
    var settings: VoiceAssistantSettings = initialSettings
        private set

    fun enable(pluginId: String) {
        val plugin = requireNotNull(registry.find(pluginId)) { "Выбранный голосовой модуль не установлен." }
        require(plugin.availability() == VoicePluginAvailability.AVAILABLE) {
            "Голосовой модуль пока недоступен или требует настройки."
        }
        settings = VoiceAssistantSettings(enabled = true, selectedPluginId = pluginId)
    }

    fun disable() {
        settings = settings.copy(enabled = false)
    }

    fun select(pluginId: String) {
        requireNotNull(registry.find(pluginId)) { "Выбранный голосовой модуль не установлен." }
        settings = settings.copy(selectedPluginId = pluginId)
    }

    fun activePlugin(): VoicePlugin? =
        if (settings.enabled) registry.find(settings.selectedPluginId) else null

    fun styleFor(isWorkConversation: Boolean?): VoiceStyleProfile =
        if (isWorkConversation == false) VoiceStyleProfile.PERSONAL_INFORMAL_ORIGINAL
        else VoiceStyleProfile.WORK_FORMAL_ORIGINAL
}

package com.dotrino.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * LOS PERFILES DEL TELÉFONO, como los lista la identidad (`listProfiles`): los mismos que ve el
 * menú del topbar en la web. Viven en el almacén de la identidad (`kv:dotrino.identity.profiles`,
 * el activo en `kv:dotrino.identity.current` y el `me` de cada uno), así que una app nativa los
 * lee y cambia de perfil igual que la web: escribiendo cuál es el activo.
 */
object PhoneProfiles {
    const val CURRENT = "kv:dotrino.identity.current"
    private const val LIST = "kv:dotrino.identity.profiles"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [login]: la dirección con la que se entró con contraseña (`nombre@AB12-CD34-EF56`), o null
     * para un perfil tuyo de siempre. [seed] es de lo que sale su identicon (su llave).
     */
    data class Entry(val id: String, val name: String?, val seed: String, val avatar: String?, val current: Boolean, val login: String?)

    /** La lista, del almacén de la identidad; vacía si el teléfono todavía no tiene perfil. */
    fun list(items: Map<String, String>): List<Entry> {
        val current = items[CURRENT]
        val list = items[LIST]?.let { runCatching { json.parseToJsonElement(it) as? JsonArray }.getOrNull() } ?: return emptyList()
        return list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val me = items["kv:dotrino.identity.p.$id.me"]?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            val name = listOf((me?.get("nickname") as? JsonPrimitive)?.content, (o["name"] as? JsonPrimitive)?.content).firstOrNull { !it.isNullOrBlank() }
            val avatar = (me?.get("avatar") as? JsonPrimitive)?.content?.takeIf { it.startsWith("data:image/") }
            val login = ((o["login"] as? JsonObject)?.get("address") as? JsonPrimitive)?.content
            Entry(id, name, (o["pubkey"] as? JsonPrimitive)?.content ?: id, avatar, id == current, login)
        }
    }

    // ----- over the identity app (Android) -----

    private suspend fun items(c: IdentityClient): Map<String, String> =
        (c.call("storeLoad")["items"] as? JsonObject).orEmpty().mapValues { (it.value as JsonPrimitive).content }

    suspend fun load(c: IdentityClient): List<Entry> = list(items(c))
    suspend fun currentPid(c: IdentityClient): String? = items(c)[CURRENT]

    /** SWITCH PROFILE, as the web does (`switchProfile`): only the active one changes, and the app restarts. */
    suspend fun switchTo(c: IdentityClient, pid: String) {
        c.call("storeSet", JsonObject(mapOf("k" to JsonPrimitive(CURRENT), "v" to JsonPrimitive(pid))))
    }
}

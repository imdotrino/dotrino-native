package com.dotrino.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.coroutineContext

/**
 * What [Friends] needs from the transport: the sealed session. An interface so it is tested in
 * the JVM with two instances wired to each other, without a proxy.
 */
interface FriendsTransport {
    /**
     * Sealed by token (live; can go direct). Empty keys = the ones the identity announced.
     * [peerPubkey]: whose the token is — if it is dead (they restarted), the same envelope goes
     * to their queue instead of being lost.
     */
    suspend fun sendSealedTo(token: String, payload: JsonObject, recipientEncPubs: List<String> = emptyList(), peerPubkey: String? = null)
    /** Sealed by pubkey (the offline queue). */
    suspend fun sendSealed(pubkey: String, payload: JsonObject, recipientEncPubs: List<String> = emptyList(), quiet: Boolean = false)
    suspend fun encPubOf(publickey: String): String
    fun pubkeyOfToken(token: String): String?
    suspend fun whoIs(token: String): String?
    suspend fun requestPairingCode(): ProxyConnection.PairingCode
    suspend fun redeemPairingCode(code: String): String
    val isOnline: Boolean
    fun onMessage(l: (SealedSession.Message) -> Unit): () -> Unit
    fun onOnline(l: () -> Unit): () -> Unit
    fun onPeerGone(l: (String) -> Unit): () -> Unit
}

/** The sealed session as the transport of [Friends] (and of the app that mounts it). */
class SessionTransport(private val s: SealedSession) : FriendsTransport {
    override suspend fun sendSealedTo(token: String, payload: JsonObject, recipientEncPubs: List<String>, peerPubkey: String?) = s.sendSealedTo(token, payload, recipientEncPubs, peerPubkey)
    override suspend fun sendSealed(pubkey: String, payload: JsonObject, recipientEncPubs: List<String>, quiet: Boolean) = s.sendSealed(pubkey, payload, recipientEncPubs, quiet)
    override suspend fun encPubOf(publickey: String) = s.encPubOf(publickey)
    override fun pubkeyOfToken(token: String) = s.pubkeyOfToken(token)
    override suspend fun whoIs(token: String) = s.whoIs(token)
    override suspend fun requestPairingCode() = s.requestPairingCode()
    override suspend fun redeemPairingCode(code: String) = s.redeemPairingCode(code)
    override val isOnline get() = s.isOnline
    override fun onMessage(l: (SealedSession.Message) -> Unit) = s.onMessage(l)
    override fun onOnline(l: () -> Unit) = s.onOnline(l)
    override fun onPeerGone(l: (String) -> Unit) = s.onEvent { e -> if (e is ProxyConnection.Event.PeerGone) l(e.token) }
}

/** Small per-account values (the requests inbox, an app's nickname): SharedPreferences in an app, a map in tests. */
interface Kv {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

class MemoryKv : Kv {
    private val m = HashMap<String, String>()
    override fun get(key: String) = m[key]
    override fun set(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
}

/**
 * LOS AMIGOS del ecosistema, en el teléfono: el puerto de `@dotrino/profile/friends`
 * (`dotrino-profile/src/friends/`), mismo cable y mismas reglas. Salió del messenger para que
 * cualquier app nativa añada amigos y los reciba (dueño, 2026-10-23). La libreta es la del perfil
 * ([PeerBook]), la misma que leen todas las apps.
 *
 *  - TODO va sellado (el transporte lo exige en los dos sentidos).
 *  - QUIÉN ESCRIBIÓ lo dice la llave que selló el sobre: una que conozco de ese amigo, o la que
 *    esa identidad anunció firmada. El token no dice nada.
 *  - Una SOLICITUD (`CONTACT_REQUEST`) es un mensaje de control: va a la bandeja, nunca a un chat, y
 *    nadie es amigo de nadie hasta que el otro acepta (`CONTACT_ACCEPT`, que solo cuenta si yo lo pedí).
 *    Que lleguen varias a la vez es lo normal: aquí no hay incidente.
 *  - A un amigo se le escribe a TODOS los aparatos de su cuenta (su tarjeta de perfil).
 *
 * Lo que es de la app (hilos, avisos, su versión) entra por los ganchos. Todo lo que cambia estado
 * corre en UN carril ([lane]): las llamadas llegan de varios hilos.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Friends(
    private val transport: FriendsTransport,
    val profile: Profile,
    val peers: PeerBook,
    private val kv: Kv,
    private val nickname: () -> String,
    private val version: Compat.Declaration?,
    /** Contactos fijos (el messenger fija la cuenta de Dotrino): no se bloquean y sin tarjeta no tienen aparatos. */
    private val pinned: List<String> = emptyList(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val REQUEST = "CONTACT_REQUEST"
        const val ACCEPT = "CONTACT_ACCEPT"
        const val REQUEST_TTL = 24 * 60 * 60 * 1000L
        private const val KV_REQUESTS = "requests"
        private val json = Json { ignoreUnknownKeys = true }

        /** El alfabeto de los códigos del proxio, sin lo que se confunde al leer o al dictar. */
        val CODE_RE = Regex("^[1-9ACDEFHJKMNOPQRTUVWXY]{6}$")
        private val CONFUSABLES = mapOf('I' to '1', 'L' to '1', 'S' to '5', 'Z' to '2', 'B' to '8', 'G' to '6', '0' to 'O')

        /** Lo tecleado, normalizado: mayúsculas, sin separadores, y lo que nunca se emite traducido. */
        fun normalizeCode(raw: String) = raw.uppercase().filter { it != ' ' && it != '-' && it != '_' }.map { CONFUSABLES[it] ?: it }.joinToString("")

        /** El código de lo que devolvió un escáner: un enlace con `#add=`, o el código pelado. */
        fun codeFromText(text: String): String =
            normalizeCode(Regex("[#&?]add=([^&\\s]+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1) ?: text.trim()).take(6)

        /** `sanitizeNickname` de la PWA: sin espacios en los bordes ni los caracteres que lo hacen ambiguo. */
        fun sanitizeNickname(s: String?): String = (s ?: "").trim().replace(Regex("[<>\"'`]"), "").take(40)
    }

    /** Una solicitud: entrante (alguien quiere añadirme) o saliente (esperando a que acepte). */
    data class Request(val pubkey: String, val dir: String, val nickname: String, val token: String?, val encryptionPubkey: String?, val ts: Long, val vouched: Boolean = false)

    /** [pubkey]: la llave por la que se le guarda (su cuenta); [device]: el aparato que escribió. */
    data class Who(val pubkey: String, val contact: JsonObject?, val encPub: String, val device: String)

    /** Lo que llegó: [handled] si era del protocolo de amigos; si no, la app sigue con [who]. */
    data class Received(val who: Who, val handled: Boolean)

    class FriendsError(message: String, val code: String) : Exception(message)

    private val lane = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + lane)
    private val online = HashMap<String, String>()          // cuenta → token vivo (esta sesión)
    private val deviceTokens = HashMap<String, String>()    // llave de APARATO → token vivo
    private var codeJob: Job? = null

    @Volatile var code: String? = null; private set

    // ---------- ganchos de la app ----------

    var onChange: () -> Unit = {}
    var onWarn: (String, Throwable?) -> Unit = { _, _ -> }
    /** Llegó una solicitud a la bandeja (avisar, según la app). */
    var onRequest: (Request) -> Unit = {}
    /** Ya sois amigos (sale lo que esperaba). */
    var onFriend: suspend (String) -> Unit = {}
    /** Un amigo guardado por la llave de un aparato pasó a su cuenta: la app mueve lo suyo (un hilo). */
    var onMoved: suspend (String, String) -> Unit = { _, _ -> }
    /** Llegó su versión (§14) y su tarjeta, venga en el mensaje que venga. */
    var onProfile: (String, JsonObject) -> Unit = { _, _ -> }
    /** ¿Alguien de mi red lo avala? (la reputación es de la app). */
    var isVouched: suspend (String) -> Boolean = { false }

    private fun changed() = runCatching { onChange() }

    fun close() { scope.cancel() }

    // ---------- la libreta ----------

    fun isPinned(pubkey: String?) = pinned.any { sameKey(pubkey, it) }

    suspend fun isBlocked(pubkey: String): Boolean = !isPinned(pubkey) && peers.isBlocked(pubkey)

    /** Bloquear o desbloquear a alguien: privado, en la libreta (viaja a la bóveda). A un fijo no. */
    suspend fun setBlocked(pubkey: String, blocked: Boolean) {
        if (isPinned(pubkey)) throw FriendsError("a pinned contact cannot be blocked", "pinned-contact")
        peers.setBlocked(pubkey, blocked); changed()
    }

    /** El amigo con esa llave (escrita como sea). */
    suspend fun find(pubkey: String): JsonObject? = peers.contacts().firstOrNull { sameKey(str(it, "publickey"), pubkey) }

    /**
     * A qué aparatos se le escribe: los de su CUENTA (su tarjeta), cada uno con su llave de cifrado;
     * o, sin tarjeta todavía, la llave con la que se añadió con todas las llaves que conozco. Un fijo
     * sin tarjeta (Dotrino antes de escribir) no tiene ninguno.
     */
    suspend fun recipientsOf(pubkey: String): List<Pair<String, List<String>>> {
        val devices = peers.devicesOf(pubkey)
        if (devices.isNotEmpty()) return devices.map { it.first to listOf(it.second) }
        if (isPinned(pubkey)) return emptyList()
        return listOf(pubkey to peers.encPubsOf(pubkey))
    }

    /** El amigo que escribe, aunque lo haga desde OTRO de sus aparatos (lo dice su tarjeta). */
    private suspend fun findBySender(pubkey: String): JsonObject? {
        val cs = peers.contacts()
        cs.firstOrNull { sameKey(str(it, "publickey"), pubkey) }?.let { return it }
        return cs.firstOrNull { c -> (peers.cardOf(str(c, "publickey")!!)?.get("keys") as? JsonArray).orEmpty().any { sameKey(str(it as? JsonObject, "pub"), pubkey) } }
    }

    // ---------- presencia ----------

    fun markOnline(pubkey: String, token: String) { synchronized(online) { online[pubkey] = token }; changed() }
    fun markOffline(token: String) {
        synchronized(online) { online.entries.removeAll { it.value == token }; deviceTokens.entries.removeAll { it.value == token } }
        changed()
    }
    fun isOnline(pubkey: String) = synchronized(online) { online.containsKey(pubkey) }
    fun liveTokenFor(pubkey: String) = synchronized(online) { online[pubkey] }

    // ---------- tu código ----------

    /**
     * Pide al proxio un código de 6 caracteres (una CITA que caduca en minutos) y lo renueva antes
     * de que caduque. Es de UN uso: cuando alguien lo canjea, se pide otro solo.
     */
    suspend fun refreshCode(): Unit = withContext(lane) {
        // La renovación llama a esto desde DENTRO de su propio job: cancelarlo aquí cancelaba la
        // petición que lo estaba renovando, y el código se quedaba vacío.
        val prev = codeJob; codeJob = null
        if (prev != null && prev != coroutineContext[Job]) prev.cancel()
        try {
            val c = transport.requestPairingCode()
            code = c.code
            val left = c.expiresAt - now()
            if (left > 10_000) codeJob = scope.launch { delay(left - 5_000); refreshCode() }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { code = null; onWarn("pairing code", e) }
        changed()
    }

    // ---------- quién soy ----------

    /** Lo que mando de mí: apodo, versión y mi tarjeta de perfil (para que me escriban a TODOS mis aparatos). */
    fun me(): JsonObject = buildJsonObject {
        put("nickname", nickname())
        version?.let { put("v", it.toJson()) }
        profile.card?.let { put("card", it) }
    }

    // ---------- escribirle a un amigo ----------

    /**
     * Sella a un amigo: a TODOS los aparatos de su cuenta, cada uno por su camino más directo — por
     * token si está en línea en esta sesión (el que puede ir directo), por pubkey si no (la cola
     * offline de cada aparato). Falla solo si no llegó a ninguno; el mismo mensaje dos veces lo
     * descarta el otro lado por su id. [quiet]: sin timbrar su teléfono.
     */
    suspend fun send(pubkey: String, payload: JsonObject, quiet: Boolean = false) {
        val to = recipientsOf(pubkey)
        if (to.isEmpty()) throw FriendsError("no device of this account is known yet", "no-devices")
        sendToDevices(pubkey, payload, to, quiet)
    }

    private suspend fun sendToDevices(contact: String, payload: JsonObject, to: List<Pair<String, List<String>>>, quiet: Boolean = false) {
        if (to.isEmpty()) throw FriendsError("no device of this account is known", "no-devices")
        var sent = 0
        var last: Exception? = null
        for ((device, keys) in to) {
            val token = synchronized(online) { deviceTokens[device] ?: (if (to.size == 1) online[contact] else null) }
            try {
                if (token != null) transport.sendSealedTo(token, payload, keys, device)
                else transport.sendSealed(device, payload, keys, quiet)
                sent++
            } catch (e: Exception) { last = e }
        }
        if (sent == 0) throw last ?: FriendsError("nothing could be sent", "send-failed")
    }

    // ---------- la bandeja ----------

    fun requests(): List<Request> {
        val raw = kv.get(KV_REQUESTS) ?: return emptyList()
        val arr = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        val t = now()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Request(str(o, "pubkey") ?: return@mapNotNull null, str(o, "dir") ?: "in", str(o, "nickname") ?: "", str(o, "token"),
                str(o, "encryptionPubkey"), long(o, "ts") ?: 0, (o["vouched"] as? JsonPrimitive)?.content == "true")
        }.filter { t - it.ts < REQUEST_TTL }
    }
    fun incoming() = requests().filter { it.dir == "in" }
    fun outgoing() = requests().filter { it.dir == "out" }

    private fun saveRequests(l: List<Request>) = kv.set(KV_REQUESTS, buildJsonArray {
        l.forEach { r -> add(buildJsonObject {
            put("pubkey", r.pubkey); put("dir", r.dir); put("nickname", r.nickname); r.token?.let { put("token", it) }
            r.encryptionPubkey?.let { put("encryptionPubkey", it) }; put("ts", r.ts); put("vouched", r.vouched)
        }) }
    }.toString())

    private fun upsertRequest(r: Request) {
        val l = requests().toMutableList()
        val i = l.indexOfFirst { sameKey(it.pubkey, r.pubkey) && it.dir == r.dir }
        if (i >= 0) l[i] = r.copy(ts = l[i].ts) else l.add(0, r)
        saveRequests(l)
    }

    private fun removeRequests(pubkey: String, dir: String? = null) =
        saveRequests(requests().filterNot { sameKey(it.pubkey, pubkey) && (dir == null || it.dir == dir) })

    private fun requestOf(pubkey: String?, dir: String) = pubkey?.let { pk -> requests().firstOrNull { sameKey(it.pubkey, pk) && it.dir == dir } }

    // ---------- pedir y aceptar ----------

    /**
     * Canjea el código de alguien y le manda una SOLICITUD. Lanza `own` (es el tuyo), `offline` (sin
     * red: espera) o `invalid` (no vale: pide otro). Se arreglan distinto, por eso se distinguen.
     */
    suspend fun requestByCode(code: String, alias: String): Unit = withContext(lane) {
        val c = normalizeCode(code)
        if (!CODE_RE.matches(c)) throw FriendsError("that is not a code", "invalid")
        if (c == this@Friends.code) throw FriendsError("that is your own code", "own")
        if (!transport.isOnline) throw FriendsError("not connected", "offline")
        val token = try { transport.redeemPairingCode(c) } catch (e: Exception) {
            val k = (e as? ProxyConnection.ProxyError)?.code ?: (e as? SealedSession.SessionError)?.code
            throw FriendsError(e.message ?: "redeem failed", if (k == "disconnected" || k == "timeout") "offline" else "invalid")
        }
        val pubkey = transport.whoIs(token) ?: throw FriendsError("the other side did not say who it is", "offline")
        sendRequest(token, pubkey, alias, null)
    }

    /**
     * Una solicitud a una llave que ya se sabe: un enlace `#to=` (con su tarjeta, va a todos los
     * aparatos de la cuenta con las llaves que ella lista) o una tarjeta vista «Cerca».
     */
    suspend fun request(pubkey: String, alias: String = "", card: JsonObject? = null, token: String? = null): Unit = withContext(lane) {
        if (!transport.isOnline) throw FriendsError("not connected", "offline")
        sendRequest(token, pubkey, alias, card)
    }

    private suspend fun sendRequest(token: String?, pubkey: String, alias: String, card: JsonObject?) {
        // Si ya me lo pidió, esto es aceptar: los dos lo quieren.
        if (requestOf(pubkey, "in") != null) { acceptNow(pubkey); return }
        val payload = JsonObject(me() + ("type" to JsonPrimitive(REQUEST)))
        val account = card?.let { c ->
            runCatching { peers.adoptPeerCard(c) }.onFailure { onWarn("the card in the link could not be adopted", it) }
            peers.accountFromCard(c, pubkey).also { if (it == null) onWarn("the card in the link does not prove that key: sending to the key alone", null) }
        }
        if (account != null) {
            sendToDevices(account, payload, peers.devicesOf(account).map { it.first to listOf(it.second) })
            upsertRequest(Request(account, "out", sanitizeNickname(alias), null, null, now()))
            changed(); return
        }
        if (token != null) transport.sendSealedTo(token, payload, peerPubkey = pubkey) else transport.sendSealed(pubkey, payload)
        upsertRequest(Request(pubkey, "out", sanitizeNickname(alias), token, null, now()))
        changed()
    }

    /** Acepta una solicitud: amigos en los dos lados. Nada entra a ningún chat. */
    suspend fun accept(pubkey: String): Unit = withContext(lane) { acceptNow(pubkey) }

    private suspend fun acceptNow(pubkey: String) {
        val r = requestOf(pubkey, "in") ?: return
        becomeFriends(pubkey, r.nickname, r.token, r.encryptionPubkey)
        r.token?.let { markOnline(pubkey, it) }
        replyAccept(pubkey)
        changed()
    }

    /** Descarta una que llegó, o cancela una que mandé. Al otro no se le dice. */
    suspend fun dismiss(pubkey: String, dir: String): Unit = withContext(lane) { removeRequests(pubkey, dir); changed() }

    /** [pubkey]: la llave por la que se guarda (su cuenta si su tarjeta lo prueba); [device]: la que escribió. */
    private suspend fun becomeFriends(pubkey: String, nickname: String, token: String?, encPub: String?, device: String? = null) {
        // La misma llave puede venir escrita de otra forma: se queda en el registro que ya hay.
        val existing = find(pubkey) ?: peers.get(pubkey)
        val key = str(existing, "publickey") ?: pubkey
        peers.addContact(key,
            nickname = str(existing, "nickname")?.takeIf { it.isNotEmpty() } ?: nickname.ifEmpty { pubkey.take(8) },
            encryptionPubkey = encPub ?: str(existing, "encryptionPubkey"),
            lastToken = token)
        removeRequests(key)
        if (device != null && !sameKey(device, key)) removeRequests(device)
        runCatching { onFriend(key) }.onFailure { onWarn("onFriend", it) }
    }

    private suspend fun replyAccept(pubkey: String) =
        runCatching { send(pubkey, JsonObject(me() + ("type" to JsonPrimitive(ACCEPT)))) }
            .onFailure { onWarn("accept reply", it) }

    // ---------- lo que llega ----------

    /**
     * QUIÉN MANDÓ ESTO: vale solo si la llave que lo selló es de la identidad que dice — una que
     * conozco de ese amigo, o la que esa identidad anunció firmada.
     */
    private suspend fun authenticate(m: SealedSession.Message): Who? {
        // Por token tras un reinicio la pubkey puede no venir (aún no saludamos ese token nuevo).
        // La llave que SELLÓ dice qué amigo es, como en la PWA; y si no es de nadie conocido, se le
        // pregunta al token de quién es.
        val claimed = m.fromPubkey
            ?: peers.contacts().firstOrNull { c -> peers.encPubsOf(str(c, "publickey")!!).any { sameKey(it, m.senderEncPub) } }?.let { str(it, "publickey") }
            ?: m.fromToken?.let { t -> runCatching { transport.whoIs(t) }.getOrNull() }
            ?: run { onWarn("message from a token nobody said whose it is — dropped", null); return null }
        val contact = findBySender(claimed)
        if (contact != null && peers.encPubsOf(str(contact, "publickey")!!).any { sameKey(it, m.senderEncPub) }) {
            return Who(str(contact, "publickey")!!, contact, m.senderEncPub, claimed)
        }
        val announced = try { transport.encPubOf(claimed) } catch (e: Exception) { onWarn("could not verify the sender key", e); return null }
        if (!sameKey(announced, m.senderEncPub)) { onWarn("message sealed with a key that is not the sender's — dropped", null); return null }
        return Who(str(contact, "publickey") ?: claimed, contact, m.senderEncPub, claimed)
    }

    /**
     * LO QUE LLEGA. Autentica, descarta lo de quien bloqueaste y atiende las solicitudes. `null` si no
     * hay que mirarlo más; si no, [Received]: `handled` si era del protocolo de amigos, y si no, la
     * app sigue con `who` (que trae `contact` solo si es amigo: lo de un desconocido no va a ningún sitio).
     */
    suspend fun receive(m: SealedSession.Message): Received? = withContext(lane) {
        val type = str(m.payload, "type") ?: return@withContext null
        val who = authenticate(m) ?: return@withContext null
        m.fromToken?.let { t -> synchronized(online) { deviceTokens[who.device] = t } }
        // BLOQUEADO (el indicador privado de la libreta): no entra nada suyo —ni mensajes ni
        // solicitudes— y no se le contesta nada, ni el acuse que le diría que llegó.
        if (isBlocked(who.pubkey) || (m.fromPubkey?.let { isBlocked(it) } == true)) return@withContext null
        when (type) {
            REQUEST -> { onContactRequest(m, who); Received(who, true) }
            ACCEPT -> { onContactAccept(m, who); Received(who, true) }
            else -> Received(who, false)
        }
    }

    /** Su versión (§14, para la app) y su tarjeta de perfil, venga en el mensaje que venga. */
    suspend fun noteProfile(pubkey: String, payload: JsonObject) {
        runCatching { onProfile(pubkey, payload) }
        (payload["card"] as? JsonObject)?.let { card ->
            val r = runCatching { peers.adoptPeerCard(card) }.getOrNull()
            if (r != null && !r.adopted && r.reason == "master-changed") onWarn("the card of ${pubkey.take(24)} is signed by another device: not adopted silently", null)
        }
    }

    /**
     * La CUENTA de quien escribe: la de su tarjeta si la firmó su perfil y la trajo el propio
     * aparato, o la llave del aparato si no hay tarjeta así. Es la llave por la que se le guarda.
     */
    private suspend fun accountOf(who: Who, payload: JsonObject): String =
        str(who.contact, "publickey") ?: peers.accountFromCard(payload["card"] as? JsonObject, who.device) ?: who.pubkey

    /**
     * Un amigo de antes, guardado por la llave de un aparato, pasa a su cuenta en cuanto ese mismo
     * aparato trae la tarjeta que lo prueba (apodo, notas y mi calificación van con él; lo de la app,
     * por [onMoved]).
     */
    suspend fun upgradeToAccount(who: Who, payload: JsonObject): JsonObject? {
        val c = who.contact ?: return null
        val old = str(c, "publickey")!!
        if (!sameKey(old, who.device)) return c
        val account = peers.accountFromCard(payload["card"] as? JsonObject, who.device) ?: return c
        if (sameKey(account, old)) return c
        try {
            peers.addContact(account, nickname = str(c, "nickname"), encryptionPubkey = str(c, "encryptionPubkey"),
                lastToken = str(c, "lastToken"), notes = str(c, "contactNotes"))
            ((c["myRating"] as? JsonObject)?.get("rating") as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { r ->
                if (peers.get(account)?.get("myRating") == null) peers.setRating(account, r, str(c["myRating"] as? JsonObject, "notes") ?: "")
            }
            onMoved(old, account)
            peers.removeContact(old)
            synchronized(online) { online.remove(old)?.let { online[account] = it } }
            onWarn("contact moved from a device key to its account", null)
        } catch (e: Exception) { onWarn("could not move the contact to its account", e); return c }
        return peers.get(account) ?: c
    }

    private suspend fun onContactRequest(m: SealedSession.Message, who: Who) {
        val nick = sanitizeNickname(str(m.payload, "nickname"))
        noteProfile(who.pubkey, m.payload)
        val contact = upgradeToAccount(who, m.payload)
        val account = str(contact, "publickey") ?: accountOf(who, m.payload)
        if (isBlocked(account)) return
        // Alguien canjeó mi código, y es de UN uso: se pide otro ya.
        scope.launch { refreshCode() }
        m.fromToken?.let { markOnline(account, it) }
        // Ya es amigo por su cuenta (un fijo, o lo añadí desde otro de sus aparatos), o yo también
        // se lo pedí: se acepta directamente.
        val known = contact ?: find(account)
        if (known != null || requestOf(who.device, "out") != null || requestOf(account, "out") != null) {
            becomeFriends(account, nick, m.fromToken, who.encPub, who.device)
            replyAccept(account)
            changed(); return
        }
        val vouched = runCatching { isVouched(account) }.getOrDefault(false)
        val r = Request(account, "in", nick, m.fromToken, who.encPub, now(), vouched)
        upsertRequest(r)
        changed()
        runCatching { onRequest(r) }
    }

    private suspend fun onContactAccept(m: SealedSession.Message, who: Who) {
        // Solo cuenta si lo pedí —a ese aparato, o a su cuenta entera (con su tarjeta, desde un
        // enlace)— o si ya es amigo por su cuenta. Nadie se añade solo a mis contactos.
        noteProfile(who.pubkey, m.payload)
        val contact = upgradeToAccount(who, m.payload)
        val account = str(contact, "publickey") ?: accountOf(who, m.payload)
        val mine = requestOf(who.device, "out") ?: requestOf(account, "out")
        val known = contact ?: find(account)
        if (mine == null && known == null) return
        becomeFriends(account, mine?.nickname?.takeIf { it.isNotEmpty() } ?: sanitizeNickname(str(m.payload, "nickname")), m.fromToken, who.encPub, who.device)
        m.fromToken?.let { markOnline(account, it) }
        changed()
    }

    // ---------- json ----------

    private fun sameKey(a: String?, b: String?) = a != null && b != null && Delegation.samePubkey(a, b)
    private fun str(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun long(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.longOrNull
}

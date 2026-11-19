package ai.nova.app.security

import ai.nova.core.auth.AuthSession
import ai.nova.core.auth.SessionStore
import ai.nova.core.data.NovaJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Сессия Supabase хранится зашифрованной ключом Android Keystore. */
class KeystoreSessionStore(private val secrets: KeystoreSecretStore) : SessionStore {
    override suspend fun load(): AuthSession? = withContext(Dispatchers.IO) {
        secrets.get(KeystoreSecretStore.SESSION)?.let { runCatching { NovaJson.decodeFromString(AuthSession.serializer(), it) }.getOrNull() }
    }

    override suspend fun save(session: AuthSession?) = withContext(Dispatchers.IO) {
        secrets.put(KeystoreSecretStore.SESSION, session?.let { NovaJson.encodeToString(AuthSession.serializer(), it) })
    }
}

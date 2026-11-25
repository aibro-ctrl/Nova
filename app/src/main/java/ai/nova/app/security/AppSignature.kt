package ai.nova.app.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Отпечаток ключа, которым подписана установленная Нова. Исходники открыты, и любой может собрать
 * похожее приложение с изменённым кодом (например, читающее коды из уведомлений). Единственный способ
 * убедиться, что стоит настоящая Нова, — сверить этот отпечаток с опубликованным в docs/SIGNING.md.
 */
object AppSignature {
    /** SHA-256 текущего сертификата подписи в виде «AB:CD:…»; null — не удалось прочитать. */
    fun fingerprint(context: Context): String? = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val cert = signatures?.firstOrNull()?.toByteArray() ?: return null
        MessageDigest.getInstance("SHA-256").digest(cert).joinToString(":") { "%02X".format(it) }
    }.getOrNull()
}

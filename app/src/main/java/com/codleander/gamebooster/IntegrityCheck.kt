package com.codleander.gamebooster

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Pins the release signing certificate. A build that was unpacked and re-signed with a
 * different key (the usual way a modded copy is made) refuses to start, so the booster
 * logic in this package cannot be repackaged into someone else's app.
 *
 * The constant is the SHA-256 of the certificate inside `app/gamebooster-release.jks`
 * and matches `RELEASE_SIGNING_FINGERPRINT.txt`. Debug builds skip the check so
 * development with the debug keystore keeps working.
 */
object IntegrityCheck {

    private const val RELEASE_CERT_SHA256 =
        "d001852454243dc41c6999fe474159cad9a00b8852d477e158cf225349e1a471"

    @Volatile
    private var cached: Boolean? = null

    fun isTrusted(context: Context): Boolean {
        cached?.let { return it }
        val trusted = BuildConfig.DEBUG || matchesReleaseCertificate(context)
        cached = trusted
        return trusted
    }

    @Suppress("DEPRECATION")
    private fun matchesReleaseCertificate(context: Context): Boolean = try {
        val pm = context.packageManager
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        certs?.any { digest(it.toByteArray()) == RELEASE_CERT_SHA256 } ?: false
    } catch (error: Throwable) {
        false
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}

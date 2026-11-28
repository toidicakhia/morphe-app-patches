package me.toidicakhia.patches.locket

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/** OkHttp 5 enforces configured pins here. Locket ships no pins, but stub it defensively. */
val LocketCertificatePinnerCheckFingerprint = Fingerprint(
    definingClass = "Lokhttp3/CertificatePinner;",
    name = "b",
    parameters = listOf(
        "Ljava/lang/String;",
        "Lkotlin/jvm/functions/Function0;"
    ),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL)
)

/** OkHttp's default hostname verifier; return true so a proxy hostname is accepted. */
val LocketOkHostnameVerifierFingerprint = Fingerprint(
    definingClass = "Lokhttp3/internal/tls/OkHostnameVerifier;",
    name = "verify",
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljavax/net/ssl/SSLSession;"
    ),
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC)
)

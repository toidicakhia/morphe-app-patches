package me.toidicakhia.patches.locket

import app.morphe.patcher.patch.PatchException
import java.security.MessageDigest

internal const val HERMES_BUNDLE_PATH = "assets/index.android.bundle"
internal const val HERMES_VERSION = 96
internal const val HERMES_SHA1_SIZE = 20

/** LoadConstUndefined r0; Ret r0 — a no-op callback for useEffect. */
private val NOOP_STUB = byteArrayOf(0x76, 0x00, 0x5C, 0x00)

private val HERMES_MAGIC = byteArrayOf(
    0xC6.toByte(), 0x1F, 0xBC.toByte(), 0x03, 0xC1.toByte(), 0x03, 0x19, 0x1F
)

/** Fails early with a clear message when the APK ships a different Hermes bundle. */
internal fun ByteArray.requireHermes96(label: String) {
    val hasMagic = size > HERMES_SHA1_SIZE &&
        copyOfRange(0, HERMES_MAGIC.size).contentEquals(HERMES_MAGIC)
    if (!hasMagic) {
        throw PatchException("$label is not a Hermes bytecode bundle")
    }

    val version = (this[8].toInt() and 0xFF) or
        ((this[9].toInt() and 0xFF) shl 8) or
        ((this[10].toInt() and 0xFF) shl 16) or
        ((this[11].toInt() and 0xFF) shl 24)
    if (version != HERMES_VERSION) {
        throw PatchException("Unsupported Hermes bytecode version $version in $label, expected $HERMES_VERSION")
    }
}

/** Hermes stores a SHA-1 of the whole file in its footer. */
internal fun ByteArray.rehashHermes() {
    val footerOffset = size - HERMES_SHA1_SIZE
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update(this, 0, footerOffset)
    digest.digest().copyInto(this, footerOffset)
}

internal fun ByteArray.indexOfSequence(sequence: ByteArray, startIndex: Int = 0): Int {
    if (sequence.isEmpty() || sequence.size > size) return -1
    for (index in startIndex..size - sequence.size) {
        if (sequence.indices.all { this[index + it] == sequence[it] }) return index
    }
    return -1
}

/**
 * Replaces a unique Hermes function body with `LoadConstTrue/False; Ret` and
 * fills the rest of the body with Unreachable (0x00). Recomputes the footer hash.
 */
internal fun ByteArray.stubHermesBoolGetter(
    pattern: ByteArray,
    value: Boolean,
    label: String
) = stubHermesFunction(
    pattern,
    if (value) byteArrayOf(0x78, 0x00, 0x5C, 0x00) else byteArrayOf(0x79, 0x00, 0x5C, 0x00),
    label
)

/** Replaces a unique Hermes function with `LoadConstUndefined; Ret` and rehashes the footer. */
internal fun ByteArray.stubHermesNoOp(pattern: ByteArray, label: String) =
    stubHermesFunction(pattern, NOOP_STUB, label)

private fun ByteArray.stubHermesFunction(pattern: ByteArray, stub: ByteArray, label: String) {
    val offset = indexOfSequence(pattern)
    if (offset == -1) {
        throw PatchException("Could not locate $label in $HERMES_BUNDLE_PATH.")
    }
    if (indexOfSequence(pattern, offset + 1) != -1) {
        throw PatchException("$label is not unique in $HERMES_BUNDLE_PATH.")
    }

    stub.copyInto(this, offset)
    fill(0x00, offset + stub.size, offset + pattern.size)
    rehashHermes()
}

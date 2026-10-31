package com.aiyu.switchboard.core

/**
 * The shared Rust core (core/src/jni_bridge.rs). Every key and the frame
 * cipher live on the Rust side; Kotlin only moves bytes.
 */
object NativeCore {
    init {
        System.loadLibrary("switchboard_core")
    }

    /** 64 bytes: secret (32) then public (32). */
    @JvmStatic external fun keypair(): ByteArray

    @JvmStatic external fun publicKey(secret: ByteArray): ByteArray?

    @JvmStatic external fun transcript(clientHello: ByteArray, serverHello: ByteArray): ByteArray

    @JvmStatic external fun codePsk(code: String): ByteArray

    /** Client-side session handle, or 0 when a key has the wrong length. Free with [sessionFree]. */
    @JvmStatic external fun sessionNew(
        staticSk: ByteArray,
        ephSk: ByteArray,
        remoteStaticPk: ByteArray,
        remoteEphPk: ByteArray,
        psk: ByteArray,
        transcript: ByteArray,
    ): Long

    @JvmStatic external fun seal(handle: Long, plaintext: ByteArray): ByteArray?

    /** Null when the frame fails authentication. */
    @JvmStatic external fun open(handle: Long, frame: ByteArray): ByteArray?

    @JvmStatic external fun sessionFree(handle: Long)
}

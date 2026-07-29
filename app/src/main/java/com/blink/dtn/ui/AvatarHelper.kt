package com.blink.dtn.ui

import com.blink.dtn.R

/**
 * Deterministic default avatars — zero network overhead.
 *
 * When a peer has no custom photo (QR / local blob), every node in the mesh
 * picks the same drawable from [DEFAULT_AVATARS] by hashing the peer's UID.
 * No avatar id is ever sent on the wire.
 */
object AvatarHelper {

    /** Local drawable pack. Swap these XML stubs for real dino PNGs later. */
    val DEFAULT_AVATARS: IntArray = intArrayOf(
        R.drawable.ic_dino_01,
        R.drawable.ic_dino_02,
        R.drawable.ic_dino_03,
        R.drawable.ic_dino_04,
        R.drawable.ic_dino_05,
        R.drawable.ic_dino_06,
        R.drawable.ic_dino_07,
        R.drawable.ic_dino_08,
        R.drawable.ic_dino_09,
        R.drawable.ic_dino_10,
        R.drawable.ic_dino_11,
        R.drawable.ic_dino_12,
        R.drawable.ic_dino_13,
        R.drawable.ic_dino_14,
        R.drawable.ic_dino_15,
        R.drawable.ic_dino_16,
    )

    /**
     * Stable mapping UID → drawable resource.
     * Same UID always yields the same index on every device / JVM.
     */
    fun getDefaultAvatarForUid(uid: String): Int {
        if (uid.isBlank() || DEFAULT_AVATARS.isEmpty()) {
            return R.drawable.ic_dino_01
        }
        val index = (uid.hashCode().toLong() and 0x7FFF_FFFFL) % DEFAULT_AVATARS.size
        return DEFAULT_AVATARS[index.toInt()]
    }
}

package net.aechronis.utils

import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound

// Vendored from Aechronis/aechronis's modules/utils -- see EntityTags.kt's kdoc for why this is a
// source copy rather than a version bump.
object OreSounds {
    val DING: Sound =
        Sound.sound(
            Key.key("minecraft:entity.experience_orb.pickup"),
            Sound.Source.PLAYER,
            0.7f,
            1.5f,
        )
}

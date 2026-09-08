package net.aechronis.utils

import net.minestom.server.tag.Tag

// Vendored from Aechronis/aechronis's modules/utils (current upstream, post-2026-08-02 monorepo
// migration) -- no newer net.aechronis:utils artifact was ever published after the pinned 86a747b
// (the old Aechronis/utils repo these came from no longer exists), so this is a source copy, not a
// version bump. See docs/LAUNCH_CHECKLIST.md's utils-audit item.
object EntityTags {
    val TRANSIENT_ENTITY: Tag<Boolean> = Tag.Boolean("aechronis:transient_entity")

    val DAMAGEABLE_MANNEQUIN: Tag<Boolean> = Tag.Boolean("aechronis:damageable_mannequin")
}

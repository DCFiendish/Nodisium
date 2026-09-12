/**
 * IncomeInventory
 *
 * Inventory container for withdrawing town income.
 */

package net.aechronis.nodes.objects

import net.minestom.server.inventory.AbstractInventory
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

class IncomeInventory {
    // normal items:
    // map material -> current amount of it in storage
    val storage: MutableMap<Material, Int> = mutableMapOf()

    @Suppress("PropertyName")
    val _inventory: Inventory = Inventory(InventoryType.CHEST_5_ROW, "Town Income")

    private var materialized = false
    private var updatingInventory = false
    private var visibleSnapshot: Map<Material, Int> = emptyMap()

    // storage/visibleSnapshot/materialized/updatingInventory are one unit of state that must
    // change together (synchronizeFromInventory's delta compare needs current+old snapshot to
    // agree, refillVisibleItems needs storage+visibleSnapshot in sync) -- a plain
    // ConcurrentHashMap on storage alone wouldn't fix that, the read-modify-write spans multiple
    // fields. add() runs from the periodic income tick (scheduler thread); getInventory()/
    // synchronizeFromInventory()/snapshot() run when a player opens/interacts with the GUI (that
    // player's own thread) -- genuinely concurrent on the same Town's IncomeInventory. One lock
    // is enough: this isn't a per-tick-per-player hot path, just occasional income adds + GUI opens.
    private val lock = Any()

    fun add(
        material: Material,
        amount: Int,
    ) {
        if (amount <= 0) return
        synchronized(lock) {
            storage[material] = (storage[material] ?: 0) + amount
            if (materialized) refillVisibleItems()
        }
    }

    fun empty(): Boolean = synchronized(lock) {
        synchronizeFromInventoryLocked()
        storage.isEmpty()
    }

    fun getInventory(): Inventory = synchronized(lock) {
        if (!materialized) {
            materialized = true
            visibleSnapshot = inventoryCounts()
        } else {
            synchronizeFromInventoryLocked()
        }
        refillVisibleItems()
        _inventory
    }

    fun owns(inventory: AbstractInventory): Boolean = inventory === _inventory

    // Stable identity for Town.incomeInventoryIndex -- doesn't materialize/synchronize the GUI
    // the way getInventory() does, just exposes the reference to key a lookup map by.
    internal val handle: AbstractInventory get() = _inventory

    fun synchronizeFromInventory(): Boolean = synchronized(lock) { synchronizeFromInventoryLocked() }

    private fun synchronizeFromInventoryLocked(): Boolean {
        if (!materialized || updatingInventory) return false
        val current = inventoryCounts()
        if (current == visibleSnapshot) return false

        (current.keys + visibleSnapshot.keys).forEach { material ->
            val delta = (current[material] ?: 0) - (visibleSnapshot[material] ?: 0)
            if (delta == 0) return@forEach
            val updated = (storage[material] ?: 0) + delta
            if (updated > 0) storage[material] = updated else storage.remove(material)
        }
        visibleSnapshot = current
        return true
    }

    fun snapshot(): Map<Material, Int> = synchronized(lock) {
        synchronizeFromInventoryLocked()
        storage.toMap()
    }

    fun pushToStorage(
        @Suppress("UNUSED_PARAMETER") force: Boolean,
    ): Boolean = synchronized(lock) { synchronizeFromInventoryLocked() }

    private fun refillVisibleItems() {
        val visible = inventoryCounts().toMutableMap()
        updatingInventory = true
        try {
            storage.forEach { (material, total) ->
                var remaining = total - (visible[material] ?: 0)
                if (remaining <= 0) return@forEach

                for (slot in 0 until _inventory.size) {
                    if (remaining <= 0) break
                    val existing = _inventory.getItemStack(slot)
                    val maxStackSize = material.maxStackSize()
                    when {
                        existing.isAir -> {
                            val added = minOf(remaining, maxStackSize)
                            _inventory.setItemStack(slot, ItemStack.of(material, added))
                            remaining -= added
                        }

                        existing.material() == material && existing.amount() < maxStackSize -> {
                            val added = minOf(remaining, maxStackSize - existing.amount())
                            _inventory.setItemStack(slot, existing.withAmount(existing.amount() + added))
                            remaining -= added
                        }
                    }
                }
            }
        } finally {
            visibleSnapshot = inventoryCounts()
            updatingInventory = false
        }
    }

    private fun inventoryCounts(): Map<Material, Int> {
        val counts = mutableMapOf<Material, Int>()
        for (slot in 0 until _inventory.size) {
            val stack = _inventory.getItemStack(slot)
            if (!stack.isAir) counts[stack.material()] = (counts[stack.material()] ?: 0) + stack.amount()
        }
        return counts
    }
}

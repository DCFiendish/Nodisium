package net.aechronis.logger.db

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Deletes raw per-change rows older than the configured retention window. Only touches the
 * high-volume block/storage/inventory/entity change tables -- feature_log (kills/loot today, the
 * natural home for any future admin-action audit log) and the rollback_operation/rollback_change
 * tables (who ran what rollback/restore, on what) are staff-accountability records, not routine
 * churn, and are kept indefinitely regardless of [Database]'s retention setting.
 */
object RetentionPruner {
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    fun pruneAsync(
        database: Database,
        retentionDays: Int,
    ): CompletableFuture<Void> {
        if (retentionDays <= 0) return CompletableFuture.completedFuture(null)
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())
        val tables =
            listOf(
                database.tableName,
                database.storageTableName,
                database.inventorySnapshotTableName,
                database.inventoryChangeTableName,
                database.entityChangeTableName,
            )
        return CompletableFuture.runAsync({
            for (table in tables) {
                runCatching {
                    database.dataSource.connection.use { connection ->
                        connection.prepareStatement("DELETE FROM \"$table\" WHERE ts < ?").use { statement ->
                            statement.setLong(1, cutoff)
                            val deleted = statement.executeUpdate()
                            if (deleted > 0) {
                                println("[Logger] pruned $deleted row(s) from \"$table\" older than $retentionDays day(s)")
                            }
                        }
                    }
                }.onFailure { failure ->
                    println("[Logger] failed to prune \"$table\": $failure")
                }
            }
        }, executor)
    }

    fun shutdown() {
        executor.shutdown()
    }
}

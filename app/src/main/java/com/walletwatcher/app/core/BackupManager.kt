package com.walletwatcher.app.core

/**
 * Coordinates safe encrypted logical-snapshot export and atomic restore across LedgerEngine and LedgerStore.
 * During restore, engine ingestion/flush/replay is paused cleanly and any events arriving mid-restore are
 * queued and replayed after the new database is active.
 */
class BackupManager(private val engine: LedgerEngine) {

    fun exportEncryptedBackup(
        password: CharArray,
        iterations: Int = BackupCodec.DEFAULT_ITERATIONS,
        nowMs: Long = System.currentTimeMillis()
    ): ByteArray {
        val snapshot = engine.store.exportLogicalSnapshot(nowMs)
        return BackupCodec.encryptSnapshot(snapshot, password, iterations)
    }

    fun restoreEncryptedBackup(
        containerBytes: ByteArray,
        password: CharArray,
        beforeSwapHook: (() -> Unit)? = null,
        duringSwapHook: (() -> Unit)? = null
    ): RestoreReport {
        val snapshot = BackupCodec.decryptSnapshot(containerBytes, password)
        return engine.withRestoreBarrier {
            engine.store.restoreFromSnapshot(
                snapshot = snapshot,
                beforeSwapHook = beforeSwapHook,
                duringSwapHook = duringSwapHook
            )
        }
    }
}

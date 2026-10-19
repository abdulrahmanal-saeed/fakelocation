package com.abdulrahman.fakelocation.system

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import java.io.File

/** The saved map tiles that let viewed areas show offline. */
object MapCache {
    private val dir: File get() = Configuration.getInstance().osmdroidTileCache

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }
}

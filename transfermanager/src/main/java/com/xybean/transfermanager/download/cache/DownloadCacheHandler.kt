package com.xybean.transfermanager.download.cache

/**
 * Author @xybean on 2018/7/16.
 */
interface DownloadCacheHandler {

    fun find(id: Int): DownloadTaskModel?

    fun replace(model: DownloadTaskModel)

    fun updateProgress(id: Int, current: Long, total: Long)

    fun updatePaused(id: Int, current: Long, total: Long)

    fun updateFailed(id: Int, e: Exception)

    fun remove(id: Int): Boolean

    fun clear()

}

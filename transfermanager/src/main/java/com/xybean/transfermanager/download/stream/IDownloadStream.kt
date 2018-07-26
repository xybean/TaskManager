package com.xybean.transfermanager.download.stream

import com.xybean.transfermanager.download.task.IDownloadTask
import java.io.OutputStream

/**
 * Author @xybean on 2018/4/17.<br></br>
 */
abstract class IDownloadStream(internal val task: IDownloadTask) {

    @Throws(Exception::class)
    abstract fun getOutputStream(): OutputStream

    abstract fun close()

    interface Factory {
        fun createDownloadStream(task: IDownloadTask): IDownloadStream
    }
}
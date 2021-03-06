package com.xybean.transfermanager.download.task

import android.os.SystemClock
import com.xybean.transfermanager.Logger
import com.xybean.transfermanager.Utils
import com.xybean.transfermanager.download.DownloadConfig
import com.xybean.transfermanager.download.DownloadListener
import com.xybean.transfermanager.download.connection.IDownloadConnection
import com.xybean.transfermanager.download.stream.IDownloadStream
import com.xybean.transfermanager.exception.NoEnoughSpaceException
import com.xybean.transfermanager.id.IdGenerator
import com.xybean.transfermanager.monitor.MonitorListener
import com.xybean.transfermanager.monitor.NetworkMonitor
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Author @xybean on 2018/7/16.
 */
internal class DownloadTask private constructor() : IDownloadTask, Runnable, Comparable<DownloadTask> {

    private companion object {
        private const val TAG = "DownloadTask"
        private const val BUFFER_SIZE = 1024
        private const val minProgressStep = 65536
        private const val minProgressTime: Long = 2000

        fun isNeedSync(bytesDelta: Long, timestampDelta: Long): Boolean {
            return bytesDelta > minProgressStep && timestampDelta > minProgressTime
        }
    }

    private lateinit var idGenerator: IdGenerator
    private var internalListener: DownloadInternalListener? = null
    private var monitorListener: MonitorListener? = null
    private lateinit var connection: IDownloadConnection
    private lateinit var outputStream: IDownloadStream
    private val headers: MutableMap<String, String> = HashMap()

    private var current: Long = 0
    private var total: Long = 0
    private var targetPath: String = ""
    private var targetName: String = ""
    private var url: String = ""
    private var listener: DownloadListener? = null
    private val status: AtomicInteger = AtomicInteger(DownloadStatus.WAIT)
    private var id = -1
    private val priority = AtomicInteger(0)

    private lateinit var networkMonitor: NetworkMonitor

    @Volatile
    private var canceled = false
    @Volatile
    private var paused = false

    override fun run() {

        if (canceled || paused) {
            Logger.d(TAG, "Task(id = $id) has been canceled or paused before start.")
            return
        }

        // 连接网络、读流、写流、存库
        try {

            if (current > 0) {
                val header = Utils.getRangeHeader(current)
                headers[header.first] = header.second
            }

            // 添加请求头
            if (!headers.isEmpty()) {
                for (key in headers.keys) {
                    connection.addHeader(key, headers[key]!!)
                }
            }

            connection.request(url)

            // 检查磁盘空间是否够用
            val contentLength = connection.getContentLength()
            val freeSpace = Utils.sdCardFreeSpace
            if (freeSpace < contentLength) {
                throw NoEnoughSpaceException(contentLength, freeSpace)
            }

            status.set(DownloadStatus.START)
            Logger.i(TAG, "Task(id = $id) start to be executed and save at ${saveAsFile()}")
            internalListener?.onStart(this)

            // 读流
            val tempFile = File(generateTempFile())
            if (current <= 0) {
                if (tempFile.exists()) {
                    Logger.d(TAG, "task(id = $id): in order to redownload file," +
                            "we delete file at ${generateTempFile()} firstly.")
                    tempFile.delete()
                }
            } else {
                Logger.i(TAG, "task(id = $id): download file by range($current).")
            }
            Utils.checkAndCreateFile(tempFile)
            val out = outputStream.getOutputStream(generateTempFile())
            val bis = connection.getInputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            var count: Int
            total = if (current > 0) {
                current + contentLength
            } else {
                contentLength
            }
            // 有内容,正常读写
            status.set(DownloadStatus.UPDATE)
            count = bis.read(buffer)
            var lastSyncBytes = current
            var lastSyncTimestamp = SystemClock.elapsedRealtime()
            while (count != -1 && !canceled && !paused) {
                out.write(buffer, 0, count)
                current += count.toLong()
                networkMonitor.update(current)
                // 更新数据库
                val now = SystemClock.elapsedRealtime()
                val bytesDelta = current - lastSyncBytes
                val timestampDelta = now - lastSyncTimestamp
                if (isNeedSync(bytesDelta, timestampDelta)) {
                    internalListener?.onUpdate(this, true)
                    lastSyncBytes = current
                    lastSyncTimestamp = now
                } else {
                    internalListener?.onUpdate(this, false)
                }
                count = bis.read(buffer)
            }
            when {
                canceled -> {
                    Logger.i(TAG, "Task(id = $id) is canceled.")
                    return
                }
                paused -> {
                    out.flush()
                    status.set(DownloadStatus.PAUSED)
                    Logger.i(TAG, "Task(id = $id) is paused.")
                    return
                }
                else -> out.flush()
            }

            // 完成后重命名文件
            val file = File(saveAsFile())
            if (file.exists()) {
                file.delete()
            }
            Utils.renameFile(generateTempFile(), saveAsFile())
            status.set(DownloadStatus.SUCCEED)
            internalListener?.onSucceed(this)

        } catch (e: Exception) {
            status.set(DownloadStatus.FAILED)
            Logger.e(TAG, "Task(id = $id) is failed.", e)
            internalListener?.onFailed(this, e)
            monitorListener?.onFailed(e, networkMonitor.toString())
        } finally {
            connection.close()
            outputStream.close()
        }

    }

    override fun compareTo(other: DownloadTask): Int {
        return other.getPriority() - priority.get()
    }

    override fun getUrl(): String {
        return url
    }

    override fun getId(): Int {
        if (id == -1) {
            id = idGenerator.getId()
        }
        return id
    }

    override fun getStatus(): Int {
        return status.get()
    }

    override fun getListener(): DownloadListener? {
        return listener
    }

    override fun setListener(listener: DownloadListener) {
        this@DownloadTask.listener = listener
    }

    override fun getTargetPath(): String {
        return targetPath
    }

    override fun getTargetName(): String {
        return targetName
    }

    override fun getCurrent(): Long {
        return current
    }

    override fun getTotal(): Long {
        return total
    }

    override fun getPriority(): Int {
        return priority.get()
    }

    override fun setPriority(priority: Int) {
        this.priority.set(priority)
    }

    fun cancel() {
        canceled = true
    }

    fun pause() {
        paused = true
    }

    internal fun bindInternalListener(listener: DownloadInternalListener) {
        internalListener = listener
    }

    private fun generateTempFile(): String {
        return saveAsFile() + "_temp"
    }

    private fun saveAsFile(): String {
        return String.format("%s/%s", targetPath, targetName)
    }

    override fun equals(other: Any?): Boolean {
        return other is DownloadTask && other.id == id
    }

    override fun hashCode(): Int {
        return id
    }

    class Builder {

        private val task: DownloadTask = DownloadTask()
        private lateinit var connectionFactory: IDownloadConnection.Factory
        private lateinit var streamFactory: IDownloadStream.Factory

        fun url(url: String) = apply {
            task.url = url
        }

        fun targetPath(path: String) = apply {
            task.targetPath = path
        }

        fun targetName(name: String) = apply {
            task.targetName = name
        }

        fun config(config: DownloadConfig) = apply {
            if (config.connectionFactory != null) {
                this@Builder.connectionFactory = config.connectionFactory!!
            }
            if (config.streamFactory != null) {
                this@Builder.streamFactory = config.streamFactory!!
            }
            if (!config.headers.isEmpty()) {
                task.headers.putAll(config.headers)
            }
            if (config.offset > 0) {
                task.current = config.offset
            }
            if (config.total > 0) {
                task.total = config.total
            }
            if (config.idGenerator != null) {
                task.idGenerator = config.idGenerator!!
            }
            if (config.monitor != null) {
                task.monitorListener = config.monitor
            }
            task.priority.set(config.priority)
        }

        fun build(): DownloadTask {
            task.connection = connectionFactory.createConnection(task)
            task.outputStream = streamFactory.createDownloadStream(task)
            task.networkMonitor = NetworkMonitor(task.getId(), NetworkMonitor.TYPE_DOWNSTREAM)
            return task
        }

    }
}

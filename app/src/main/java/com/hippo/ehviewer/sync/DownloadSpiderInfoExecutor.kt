package com.hippo.ehviewer.sync

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hippo.ehviewer.callBack.SpiderInfoReadCallBack
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DownloadSpiderInfoExecutor(
    private val mList: MutableList<DownloadInfo>,
    private val callBack: SpiderInfoReadCallBack?
) {
    var handler: Handler = Handler(Looper.getMainLooper())
    private val service: ExecutorService = Executors.newSingleThreadExecutor()

    val resultMap: MutableMap<Long?, SpiderInfo?> = HashMap<Long?, SpiderInfo?>()


    fun execute() {
        service.execute(Runnable {
            for (i in mList.indices) {
                val info = mList.get(i)
                try {
                    resultMap.put(info.gid, getSpiderInfo(info))
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "OOM reading spider info header for gid=" + info.gid, e)
                    resultMap.put(info.gid, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed reading spider info header for gid=" + info.gid, e)
                    resultMap.put(info.gid, null)
                }
            }
            handler.post(Runnable {
                if (callBack == null) {
                    return@Runnable
                }
                callBack.resultCallBack(resultMap)
            })
        })
    }

    private fun getSpiderInfo(info: GalleryInfo): SpiderInfo? {
        // getGalleryDownloadDir() below would record a dirname, inventing a local download.
        if (com.hippo.ehviewer.smb.SmbTaskInfo.isSmb(info as? DownloadInfo)) {
            return com.hippo.ehviewer.smb.SmbGalleryFiles.openSpiderInfoInputStream(info)
                ?.use { SpiderInfo.readHeader(it) }
                ?.takeIf { it.gid == info.gid }
        }
        val spiderInfo: SpiderInfo?
        val mDownloadDir = SpiderDen.getGalleryDownloadDir(info)
        if (mDownloadDir != null && mDownloadDir.isDirectory()) {
            val file = mDownloadDir.findFile(SpiderQueen.SPIDER_INFO_FILENAME)
            spiderInfo = SpiderInfo.readHeader(file)
            if (spiderInfo != null && spiderInfo.gid == info.gid &&
                spiderInfo.token == info.token
            ) {
                return spiderInfo
            }
        }
        return null
    }

    companion object {
        private const val TAG = "DownloadSpiderInfoExec"
    }
}

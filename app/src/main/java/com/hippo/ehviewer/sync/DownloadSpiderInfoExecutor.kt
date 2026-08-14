package com.hippo.ehviewer.sync

import android.os.Handler
import android.os.Looper
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
                resultMap.put(info.gid, getSpiderInfo(info))
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
        // An SMB save keeps its reading position beside its images on the share (#59). Reading is
        // reading -- how far through a gallery you are does not depend on where its bytes live --
        // but the phone's download folder is the wrong place to look for it, and asking there has
        // a side effect: getGalleryDownloadDir() records a download directory name in the
        // database for any gallery that has none, which for one that was never downloaded here
        // invents a local download that does not exist.
        if (com.hippo.ehviewer.smb.SmbTaskInfo.isSmb(info as? DownloadInfo)) {
            return com.hippo.ehviewer.smb.SmbGalleryFiles.openSpiderInfoInputStream(info)
                ?.use { SpiderInfo.read(it) }
                ?.takeIf { it.gid == info.gid }
        }
        val spiderInfo: SpiderInfo?
        val mDownloadDir = SpiderDen.getGalleryDownloadDir(info)
        if (mDownloadDir != null && mDownloadDir.isDirectory()) {
            val file = mDownloadDir.findFile(SpiderQueen.SPIDER_INFO_FILENAME)
            spiderInfo = SpiderInfo.read(file)
            if (spiderInfo != null && spiderInfo.gid == info.gid &&
                spiderInfo.token == info.token
            ) {
                return spiderInfo
            }
        }
        return null
    }
}

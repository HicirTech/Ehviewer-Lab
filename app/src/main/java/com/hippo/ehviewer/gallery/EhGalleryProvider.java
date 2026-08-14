/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.gallery;

import com.hippo.ehviewer.smb.SmbSpiderStorage;
import android.content.Context;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import com.hippo.util.IoThreadPoolExecutor;
import com.hippo.ehviewer.spider.RemotePageBridge;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.R;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.lib.glgallery.GalleryProvider;
import com.hippo.lib.image.Image;
//import com.hippo.lib.image.Image1;
import com.hippo.unifile.UniFile;
import com.hippo.lib.yorozuya.SimpleHandler;
import java.util.Locale;

public class EhGalleryProvider extends GalleryProvider2 implements SpiderQueen.OnSpiderListener {

    private final Context mContext;
    private final GalleryInfo mGalleryInfo;
    @Nullable
    private SpiderQueen mSpiderQueen;

    public EhGalleryProvider(Context context, GalleryInfo galleryInfo) {
        mContext = context;
        mGalleryInfo = galleryInfo;
    }

    @Override
    public void start() {
        super.start();

        mSpiderQueen = SpiderQueen.obtainSpiderQueen(mContext, mGalleryInfo, SpiderQueen.MODE_READ);
        mSpiderQueen.addOnSpiderListener(this);
    }

    @Override
    public void stop() {
        super.stop();

        if (mSpiderQueen != null) {
            mSpiderQueen.removeOnSpiderListener(this);
            // Activity recreate may called, so wait 3000s
            SimpleHandler.getInstance().postDelayed(new ReleaseTask(mSpiderQueen), 3000);
            mSpiderQueen = null;
        }
    }

    @Override
    public int getStartPage() {
        if (mSpiderQueen != null) {
            return mSpiderQueen.getStartPage();
        } else {
            return super.getStartPage();
        }
    }

    @NonNull
    @Override
    public String getImageFilename(int index) {
        return String.format(Locale.US, "%d-%s-%08d", mGalleryInfo.gid, mGalleryInfo.token, index + 1);
    }

    @Override
    public boolean save(int index, @NonNull UniFile file) {
        if (null != mSpiderQueen) {
            return mSpiderQueen.save(index, file);
        } else {
            return false;
        }
    }

    @Nullable
    @Override
    public UniFile save(int index, @NonNull UniFile dir, @NonNull String filename) {
        if (null != mSpiderQueen) {
            return mSpiderQueen.save(index, dir, filename);
        } else {
            return null;
        }
    }

    @Override
    public void putStartPage(int page) {
        if (mSpiderQueen != null) {
            mSpiderQueen.putStartPage(page);
        }
    }

    @Override
    public int size() {
        if (mSpiderQueen != null) {
            return mSpiderQueen.size();
        } else {
            return GalleryProvider.STATE_ERROR;
        }
    }

    @Override
    protected void onRequest(int index) {
        if (mSpiderQueen != null) {
            Object object = mSpiderQueen.request(index);
            if (object instanceof Float) {
                notifyPagePercent(index, (Float) object);
            } else if (object instanceof String) {
                notifyPageFailed(index, (String) object);
            } else if (object == null) {
                notifyPageWait(index);
            }
        }
    }

    /**
     * Pages the reader has asked to re-fetch, and whose fresh bytes should therefore also replace
     * the copy on the SMB share (#16).
     *
     * <p>Only what the user pressed refresh on. An ordinary page turn must never write to the
     * share — see {@code SpiderDen.openOutputStreamPipe} — and this is the exception that proves
     * it: a page whose file on the share is corrupt reads back corrupt forever otherwise, because
     * re-downloading it only ever refreshes the cache.
     */
    private final Set<Integer> mRepairOnShare =
            Collections.synchronizedSet(new HashSet<Integer>());

    @Override
    protected void onForceRequest(int index) {
        if (SmbSpiderStorage.isGidMarkedSmbTarget(mGalleryInfo.gid)) {
            mRepairOnShare.add(index);
        }
        if (mSpiderQueen != null) {
            Object object = mSpiderQueen.forceRequest(index);
            if (object instanceof Float) {
                notifyPagePercent(index, (Float) object);
            } else if (object instanceof String) {
                notifyPageFailed(index, (String) object);
            } else if (object == null) {
                notifyPageWait(index);
            }
        }
    }

    @Override
    protected void onCancelRequest(int index) {
        if (mSpiderQueen != null) {
            mSpiderQueen.cancelRequest(index);
        }
    }

    @Override
    public String getError() {
        if (mSpiderQueen != null) {
            return mSpiderQueen.getError();
        } else {
            return "Error"; // TODO
        }
    }

    @Override
    public void onGetPages(int pages) {
        notifyDataChanged();
    }

    @Override
    public void onGet509(int index) {
        // TODO
    }

    @Override
    public void onPageDownload(int index, long contentLength, long receivedSize, int bytesRead) {
        if (contentLength > 0) {
            notifyPagePercent(index, (float) receivedSize / contentLength);
        }
    }

    @Override
    public void onPageSuccess(int index, int finished, int downloaded, int total) {
        notifyDataChanged(index);
        repairOnShareIfAsked(index);
    }

    /**
     * Writes a just-refreshed page back to the share, if that is what the refresh was for.
     *
     * <p>Runs after the fetch, not instead of it: the reader is already showing the good page by
     * now, and this is only about the copy that outlives the cache. A failure is worth saying out
     * loud — the page looks fixed, and without a word the user would find out it was not the next
     * time the cache was cleared.
     */
    private void repairOnShareIfAsked(int index) {
        if (!mRepairOnShare.remove(index)) {
            return;
        }
        final Context appContext = mContext.getApplicationContext();
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            if (RemotePageBridge.copyFromCacheToRemote(mGalleryInfo, index)) {
                return;
            }
            SimpleHandler.getInstance().post(() -> Toast.makeText(
                    appContext, R.string.smb_page_repair_failed, Toast.LENGTH_SHORT).show());
        });
    }

    @Override
    public void onPageFailure(int index, String error, int finished, int downloaded, int total) {
        notifyPageFailed(index, error);
    }

    @Override
    public void onFinish(int finished, int downloaded, int total) {
    }

    @Override
    public void onGetImageSuccess(int index, Image image) {
        notifyPageSucceed(index, image);
    }

    @Override
    public void onGetImageFailure(int index, String error) {
        notifyPageFailed(index, error);
    }

    private static class ReleaseTask implements Runnable {

        private SpiderQueen mSpiderQueen;

        public ReleaseTask(SpiderQueen spiderQueen) {
            mSpiderQueen = spiderQueen;
        }

        @Override
        public void run() {
            if (null != mSpiderQueen) {
                SpiderQueen.releaseSpiderQueen(mSpiderQueen, SpiderQueen.MODE_READ);
                mSpiderQueen = null;
            }
        }
    }
}

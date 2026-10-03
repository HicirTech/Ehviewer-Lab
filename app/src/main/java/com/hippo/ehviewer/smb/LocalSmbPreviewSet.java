package com.hippo.ehviewer.smb;

import android.os.Parcel;

import com.hippo.ehviewer.client.data.GalleryPreview;
import com.hippo.ehviewer.client.data.PreviewSet;
import com.hippo.widget.LoadImageView;

/** Holds gid and title only: a GalleryInfo back-reference recurses on parcel. */
public class LocalSmbPreviewSet extends PreviewSet {

    private final long mGid;
    private final String mTitle;
    /** A bounded slice, not the gallery's page count. */
    private final int mCount;

    public LocalSmbPreviewSet(long gid, String title, int count) {
        mGid = gid;
        mTitle = title;
        mCount = Math.max(0, count);
    }

    @Override
    public int size() {
        return mCount;
    }

    @Override
    public int getPosition(int index) {
        return index;
    }

    @Override
    public String getPageUrlAt(int index) {
        // The detail scene opens the reader by index, never by this URL.
        return "";
    }

    @Override
    public GalleryPreview getGalleryPreview(long gid, int index) {
        // Read only by GalleryPreviewsScene, unreachable offline (previewPages is 1).
        return new GalleryPreview();
    }

    @Override
    public void load(LoadImageView view, long gid, int index) {
        // Conaco loads cells serially; a parallel prefetch makes each load a buffer read.
        SmbPreviewCache.prefetchGallery(mGid, mTitle, mCount);
        view.resetClip();
        view.load(previewKey(gid, index), previewUrl(gid, index),
                new SmbImageDataContainer(mGid, mTitle, index), false, false);
    }

    private static String previewKey(long gid, int index) {
        return "smb-preview:" + gid + ":" + index;
    }

    private static String previewUrl(long gid, int index) {
        // Conaco needs a non-null URL; with useNetwork false it is never fetched.
        return "smb-preview://" + gid + "/" + index;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mGid);
        dest.writeString(mTitle);
        dest.writeInt(mCount);
    }

    protected LocalSmbPreviewSet(Parcel in) {
        mGid = in.readLong();
        mTitle = in.readString();
        mCount = in.readInt();
    }

    public static final Creator<LocalSmbPreviewSet> CREATOR = new Creator<LocalSmbPreviewSet>() {
        @Override
        public LocalSmbPreviewSet createFromParcel(Parcel source) {
            return new LocalSmbPreviewSet(source);
        }

        @Override
        public LocalSmbPreviewSet[] newArray(int size) {
            return new LocalSmbPreviewSet[size];
        }
    };
}

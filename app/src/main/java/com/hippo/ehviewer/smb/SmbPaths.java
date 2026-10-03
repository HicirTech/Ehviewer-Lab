package com.hippo.ehviewer.smb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.lib.yorozuya.FileUtils;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/** No Android dependency, so tests need no emulated SDK. */
public final class SmbPaths {

    private SmbPaths() {}

    /** smb://host[:port]/share followed by sharePath verbatim. */
    @NonNull
    public static String buildShareUrl(@Nullable String host, @Nullable String port,
                                       @Nullable String shareName, @Nullable String sharePath) {
        StringBuilder url = new StringBuilder("smb://");
        if (host != null) {
            url.append(host);
        }

        if (port != null && !port.isEmpty() && !port.equals("445")) {
            url.append(":").append(port);
        }

        String encodedShare = shareName != null ? shareName : "";
        if (!encodedShare.isEmpty()) {
            try {
                encodedShare = URLEncoder.encode(shareName, "UTF-8").replace("+", "%20");
            } catch (UnsupportedEncodingException ignored) {
                // UTF-8 is always supported.
            }
        }
        url.append("/").append(encodedShare);
        if (sharePath != null) {
            url.append(sharePath);
        }
        return url.toString();
    }

    /** One level down, so state/ is a sibling, not an entry. */
    public static final String GALLERY_DIR = "download";

    @NonNull
    public static String buildGalleryRootUrl(@NonNull String shareUrl) {
        return shareUrl.endsWith("/")
                ? shareUrl + GALLERY_DIR + "/"
                : shareUrl + "/" + GALLERY_DIR + "/";
    }

    public static final String STATE_DIR = "state";

    @NonNull
    public static String buildStateRootUrl(@NonNull String shareUrl) {
        return shareUrl.endsWith("/")
                ? shareUrl + STATE_DIR + "/"
                : shareUrl + "/" + STATE_DIR + "/";
    }

    @NonNull
    public static String buildGalleryFolderName(@NonNull GalleryInfo info) {
        return buildGalleryFolderName(info.gid, info.title);
    }

    @NonNull
    public static String buildGalleryFolderName(long gid, @Nullable String title) {
        String safe = (title == null || title.isEmpty()) ? "gallery" : title;
        return FileUtils.sanitizeFilename(gid + "-" + safe);
    }

    /** A positive gid- match: NAS system dirs such as @eaDir or #recycle have no leading dot. */
    public static boolean isGalleryFolderName(@Nullable String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        int dash = name.indexOf('-');
        // At least one digit before the dash, and something after it.
        if (dash <= 0 || dash == name.length() - 1) {
            return false;
        }
        for (int i = 0; i < dash; i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    public static final long NOT_A_GALLERY = -1L;

    /** Overflow is rejected, never wrapped: a wrong gid would mark the wrong gallery. */
    public static long parseGid(@Nullable String folderName) {
        if (!isGalleryFolderName(folderName)) {
            return NOT_A_GALLERY;
        }
        //noinspection ConstantConditions -- isGalleryFolderName rejects null
        String digits = folderName.substring(0, folderName.indexOf('-'));
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return NOT_A_GALLERY;
        }
    }
}

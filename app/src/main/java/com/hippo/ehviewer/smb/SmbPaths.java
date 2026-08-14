package com.hippo.ehviewer.smb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.lib.yorozuya.FileUtils;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/** Pure path/URL construction; no Android dependency so tests need no emulated SDK. */
public final class SmbPaths {

    private SmbPaths() {}

    /**
     * smb://host[:port]/share/path/ — default port omitted, share segment percent-encoded
     * (URLEncoder's "+" converted back to "%20"), path appended verbatim.
     */
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
                // UTF-8 is guaranteed; fall back to the raw value.
            }
        }
        url.append("/").append(encodedShare);
        if (sharePath != null) {
            url.append(sharePath);
        }
        return url.toString();
    }

    /** Galleries live here, one level down, so state/ and the index are siblings not entries. */
    public static final String GALLERY_DIR = "download";

    /** The share URL galleries are enumerated from: the configured path plus {@link #GALLERY_DIR}. */
    @NonNull
    public static String buildGalleryRootUrl(@NonNull String shareUrl) {
        return shareUrl.endsWith("/")
                ? shareUrl + GALLERY_DIR + "/"
                : shareUrl + "/" + GALLERY_DIR + "/";
    }

    /** One JSON file per client (#59); a sibling the gallery enumeration never sees. */
    public static final String STATE_DIR = "state";

    /** The share URL client state files live under: the configured path plus {@link #STATE_DIR}. */
    @NonNull
    public static String buildStateRootUrl(@NonNull String shareUrl) {
        return shareUrl.endsWith("/")
                ? shareUrl + STATE_DIR + "/"
                : shareUrl + "/" + STATE_DIR + "/";
    }

    /**
     * The per-gallery folder name on the share: {@code <gid>-<title>}, sanitised to a filesystem-safe
     * string. Falls back to {@code "gallery"} when the gallery has no title.
     */
    @NonNull
    public static String buildGalleryFolderName(@NonNull GalleryInfo info) {
        return buildGalleryFolderName(info.gid, info.title);
    }

    /** Same name from explicit gid+title — for rename (#86), so sanitising cannot diverge. */
    @NonNull
    public static String buildGalleryFolderName(long gid, @Nullable String title) {
        String safe = (title == null || title.isEmpty()) ? "gallery" : title;
        return FileUtils.sanitizeFilename(gid + "-" + safe);
    }

    /**
     * Is this folder one of ours? Tests the <gid>- prefix (survives all sanitising); NAS system
     * dirs (@eaDir, #recycle...) are not hidden-dot-prefixed. Not a regex: runs per folder per listing.
     */
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

    /** Returned instead of a gid by {@link #parseGid} when the name is not a gallery folder's. */
    public static final long NOT_A_GALLERY = -1L;

    /**
     * The gid from a folder name, or NOT_A_GALLERY. Accepts exactly what isGalleryFolderName
     * accepts; overflow is rejected, never wrapped (a wrong gid marks the wrong gallery).
     */
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

package com.hippo.ehviewer.smb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.lib.yorozuya.FileUtils;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Pure path/URL construction for the SMB layer.
 *
 * <p>Pulled out of {@code SmbStorage} so the bits that turn user-entered connection settings into
 * an {@code smb://} URL — and a {@link GalleryInfo} into a share folder name — can be unit-tested
 * without a live share or {@code Settings}/SharedPreferences. Everything here is a pure function of
 * its arguments; {@code SmbStorage} stays responsible for reading the values out of {@code Settings}
 * and passing them in.
 *
 * <p>Deliberately uses plain {@code null}/empty checks instead of {@code android.text.TextUtils} so
 * the class carries no Android framework dependency and the tests need no emulated SDK.
 */
public final class SmbPaths {

    private SmbPaths() {}

    /**
     * Builds the {@code smb://host[:port]/share/path/} URL jcifs uses to address the share root.
     *
     * <p>The default SMB port (445) is omitted to keep the URL canonical. Share names from typical
     * NAS configs may contain spaces or other reserved characters ("Public Documents", "Family$"),
     * so the share segment is percent-encoded; {@link URLEncoder} emits x-www-form-urlencoded
     * output, so its {@code "+"} is converted back to {@code "%20"} to stay within the smb-URL
     * grammar. The path segment is appended verbatim — callers are expected to pass it already
     * normalised (leading and trailing slash).
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

    /**
     * Galleries live one level down from the configured share path, rather than directly in it.
     *
     * <p>They used to sit at the top, which left nowhere to put anything else: the shared download
     * state (#59) and the gallery index (#16) would have landed among them, and the enumeration
     * would have had to tell them apart from galleries every time. A directory of their own keeps
     * those as siblings the gallery listing never sees.
     */
    public static final String GALLERY_DIR = "download";

    /** The share URL galleries are enumerated from: the configured path plus {@link #GALLERY_DIR}. */
    @NonNull
    public static String buildGalleryRootUrl(@NonNull String shareUrl) {
        return shareUrl.endsWith("/")
                ? shareUrl + GALLERY_DIR + "/"
                : shareUrl + "/" + GALLERY_DIR + "/";
    }

    /**
     * Where the devices sharing this share publish what they are downloading (#59) — one JSON file
     * per client. A sibling of {@link #GALLERY_DIR} rather than a directory among the galleries, so
     * the gallery enumeration never has to know it exists.
     */
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

    /**
     * The same name, from a gid and a title that need not be the ones a record currently holds.
     *
     * <p>Exists for renaming (#86): working out where a gallery would live under a new title means
     * building the name from that title while the record still carries the old one. Deriving it by
     * hand at the call site is how the two come to disagree about sanitising or the fallback.
     */
    @NonNull
    public static String buildGalleryFolderName(long gid, @Nullable String title) {
        String safe = (title == null || title.isEmpty()) ? "gallery" : title;
        return FileUtils.sanitizeFilename(gid + "-" + safe);
    }

    /**
     * Whether a directory sitting among the galleries is one of ours.
     *
     * <p>A share root is never only galleries. NAS software leaves its own directories there —
     * {@code @eaDir} on Synology, {@code #recycle}, {@code lost+found}, {@code .snapshot} — and
     * most of them do not begin with a dot, so "skip hidden entries" would not have caught them.
     * Treating them as galleries inflates the page count and spends an SMB round trip each,
     * looking for a {@code metadata.json} that was never going to be there.
     *
     * <p>The test is the {@code <gid>-} prefix that {@link #buildGalleryFolderName} always
     * produces. It is safe to rely on: {@code sanitizeFilename} only strips forbidden characters
     * and truncates at the tail, and neither digits nor {@code -} are forbidden, so the prefix
     * survives whatever the title does. And it costs nothing — the name is already in hand from
     * the directory enumeration, with no extra round trip.
     *
     * <p>Deliberately not a regex: this runs once per folder on every listing.
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
     * The gid a gallery folder's name begins with, or {@link #NOT_A_GALLERY}.
     *
     * <p>The folder name is the only place a gid can be read without opening anything, which is
     * what makes "which galleries are on the share?" one directory enumeration rather than one
     * {@code metadata.json} read per gallery (#83).
     *
     * <p>Accepts exactly what {@link #isGalleryFolderName} accepts, so the two cannot come to
     * disagree about which entries count. A gid too large for a long is rejected rather than
     * wrapped: a wrong gid would mark the wrong gallery, which is worse than marking none.
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

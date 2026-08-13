/*
 * Copyright 2024 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.localinventory;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.hippo.android.resource.AttrResources;
import com.hippo.easyrecyclerview.EasyRecyclerView;
import com.hippo.easyrecyclerview.MarginItemDecoration;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.smb.SmbCoverDataContainer;
import com.hippo.ehviewer.smb.SmbDeviceColor;
import com.hippo.ehviewer.smb.SmbDirectDownloader;
import com.hippo.ehviewer.smb.SmbMetadata;
import com.hippo.ehviewer.smb.SmbPaths;
import com.hippo.ehviewer.smb.SmbPreviewCache;
import com.hippo.ehviewer.smb.SmbSortMode;
import com.hippo.ehviewer.smb.SmbStorage;
import com.hippo.ehviewer.smb.SmbTaskInfo;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.ehviewer.ui.scene.ToolbarScene;
import com.hippo.ehviewer.ui.dialog.SelectItemWithIconAdapter;
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene;
import com.hippo.ehviewer.widget.SmbStatusBadge;
import com.hippo.ehviewer.widget.GalleryInfoContentHelper;
import com.hippo.ehviewer.widget.SimpleRatingView;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.lib.yorozuya.ViewUtils;
import com.hippo.ripple.Ripple;
import com.hippo.scene.Announcer;
import com.hippo.widget.ContentLayout;
import com.hippo.widget.FabLayout;
import com.hippo.widget.LoadImageView;
import com.hippo.widget.Slider;
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Browses galleries that were saved to the SMB share. Completely independent of
 * {@code FavoritesScene} — this scene only renders content read from the share and never touches Eh
 * favorites state.
 *
 * <p>Paginates exactly like the online gallery list: it drives a {@link ContentLayout} through a
 * {@link ContentLayout.ContentHelper}, so it gets the same page-by-page navigation (pull for
 * next/prev page, "go to page" jump) and the same data/scroll retention on return from a detail.
 * Each page reads only its own slice of {@code metadata.json} files, so a big share never blocks on
 * a full up-front sweep.
 */
public class LocalInventoryScene extends ToolbarScene
        implements EasyRecyclerView.OnItemClickListener, EasyRecyclerView.OnItemLongClickListener,
        FabLayout.OnClickFabListener, EasyRecyclerView.CustomChoiceListener {

    // Galleries read per page. Bounds the SMB metadata reads done before a page can render.
    private static final int PAGE_SIZE = 50;

    // Secondary FAB positions, in the order they're declared in scene_local_inventory.xml.
    //
    // There used to be a "download tasks" entry here, opening a screen of its own. SMB saves now
    // appear in the ordinary download list alongside the phone's (#59), so this screen is for
    // browsing what is already on the share and nothing else.
    private static final int FAB_SORT = 0;
    private static final int FAB_GO_TO = 1;
    private static final int FAB_REFRESH = 2;
    // Selection mode only. The FabLayout carries both sets and shows one at a time, the way the
    // favourites screen does; the alternative is two FabLayouts fighting over the same corner.
    private static final int FAB_RESYNC_SELECTED = 3;
    private static final int FAB_DELETE_SELECTED = 4;
    private static final int FAB_SELECT_ALL = 5;

    @Nullable
    private EasyRecyclerView mRecyclerView;
    @Nullable
    private InventoryAdapter mAdapter;
    @Nullable
    private InventoryHelper mHelper;
    @Nullable
    private FabLayout mFabLayout;
    private boolean mHasFirstRefresh;
    @Nullable
    private ExecutorService mExecutor;

    // ---------- "someone is downloading this" badge (#77) ----------

    /** What a card needs in order to draw its badge: whose download, and how far along. */
    private static final class DownloadMark {
        @NonNull final String clientId;
        final float progress;

        DownloadMark(@NonNull String clientId, float progress) {
            this.clientId = clientId;
            this.progress = progress;
        }

        /** Compared, not merely stored: an unchanged mark is a redraw not done. */
        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DownloadMark)) {
                return false;
            }
            DownloadMark other = (DownloadMark) o;
            return clientId.equals(other.clientId)
                    && Float.compare(progress, other.progress) == 0;
        }

        @Override
        public int hashCode() {
            return clientId.hashCode() * 31 + Float.floatToIntBits(progress);
        }
    }

    /**
     * Which device has claimed each gallery and how far it has got, by gid. Empty when nothing is
     * being downloaded, which is the ordinary case — a card asks this map and finds nothing.
     *
     * <p>Everything currently claimed on the share, not only what is actively transferring: a
     * gallery queued or paused is on disk half-written just the same, and a card that looked
     * complete while its download waited its turn would be the same lie this exists to stop. Those
     * show an empty ring, which is the honest picture of a download that has not started.
     */
    @NonNull
    private Map<Long, DownloadMark> mDownloadMarks = Collections.emptyMap();

    /** Tells the adapter to redraw a card's badge and leave the rest of it alone. */
    private static final Object PAYLOAD_BADGE = new Object();

    /**
     * How often {@code state/} may be re-read, however often something asks.
     *
     * <p>The downloader announces every finished page and this screen listens, so during a download
     * the asking is constant. Two seconds is finer than the answer can actually move for another
     * device — its progress only reaches the share on a twenty-second heartbeat — and about right
     * for this device's own, which changes with every page.
     */
    private static final long BADGE_REFRESH_INTERVAL_MS = 2_000L;

    private long mLastBadgeRefreshAt;
    private boolean mBadgeRefreshScheduled;

    private final SmbDirectDownloader.TaskObserver mSmbTaskObserver = this::refreshDownloadingBadges;

    @Override
    public int getNavCheckedItem() {
        return R.id.nav_local_inventory;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context context = getEHContext();
        if (context != null) {
            mExecutor = EhApplication.getExecutorService(context);
        }
        // Our own downloads need no round trip to notice: the downloader says so directly.
        SmbDirectDownloader.getInstance().addTaskObserver(mSmbTaskObserver);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        SmbDirectDownloader.getInstance().removeTaskObserver(mSmbTaskObserver);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Coming back from the reader or a detail page, where a download may have been started.
        refreshDownloadingBadges();
    }

    /**
     * Re-reads who is downloading what, at most every {@link #BADGE_REFRESH_INTERVAL_MS}.
     *
     * <p>Rate-limited rather than dropped: the last call of a burst is honoured late instead of
     * thrown away, or a download that finished just as the limit closed would keep its badge until
     * something else happened to ask.
     */
    private void refreshDownloadingBadges() {
        long now = System.currentTimeMillis();
        long since = now - mLastBadgeRefreshAt;
        if (since < BADGE_REFRESH_INTERVAL_MS) {
            if (!mBadgeRefreshScheduled) {
                mBadgeRefreshScheduled = true;
                SimpleHandler.getInstance().postDelayed(() -> {
                    mBadgeRefreshScheduled = false;
                    refreshDownloadingBadges();
                }, BADGE_REFRESH_INTERVAL_MS - since);
            }
            return;
        }
        mLastBadgeRefreshAt = now;

        if (!SmbStorage.isConfigured() || !Settings.getSmbSaveEnabled()) {
            applyDownloadMarks(Collections.<Long, DownloadMark>emptyMap());
            return;
        }
        Runnable task = () -> {
            final Map<Long, DownloadMark> marks = new HashMap<>();
            // Reads the share. Never throws: an unreachable share comes back as an empty list, and
            // the cards simply show no badges rather than the screen failing over a decoration.
            for (SmbTaskInfo t : SmbDirectDownloader.getInstance().snapshotSharedTasks()) {
                marks.put(t.gid, new DownloadMark(t.ownerClientId, fractionOf(t)));
            }
            SimpleHandler.getInstance().post(() -> applyDownloadMarks(marks));
        };
        if (mExecutor != null) {
            mExecutor.execute(task);
        } else {
            new Thread(task, "LocalInventoryBadges").start();
        }
    }

    /**
     * How much of a task is done, 0 to 1.
     *
     * <p>Zero when the total is not known yet. That is a real state — a gallery is claimed before
     * anyone has counted its pages — and an empty ring says it better than a full one would.
     */
    private static float fractionOf(@NonNull SmbTaskInfo t) {
        if (t.total <= 0) {
            return 0f;
        }
        return (float) t.finished / (float) t.total;
    }

    /**
     * Main thread. Redraws only the cards whose answer actually changed.
     *
     * <p>This is called every couple of seconds while anything is downloading, and now the value
     * moves each time rather than staying put, so a blanket {@code notifyDataSetChanged} would
     * rebuild the whole list on a timer. A list rebuilding under a finger loses the gesture, which
     * is how a long press comes to be swallowed. The payload keeps the rebind to the badge, so a
     * card's cover is not asked for again either.
     */
    private void applyDownloadMarks(@NonNull Map<Long, DownloadMark> marks) {
        if (mDownloadMarks.equals(marks)) {
            return;
        }
        Map<Long, DownloadMark> previous = mDownloadMarks;
        mDownloadMarks = marks;
        if (mAdapter == null || mHelper == null) {
            return;
        }
        for (int i = 0, n = mHelper.size(); i < n; i++) {
            GalleryInfo gi = mHelper.getDataAt(i);
            if (gi == null) {
                continue;
            }
            DownloadMark before = previous.get(gi.gid);
            DownloadMark after = marks.get(gi.gid);
            boolean changed = before == null ? after != null : !before.equals(after);
            if (changed) {
                mAdapter.notifyItemChanged(i, PAYLOAD_BADGE);
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView3(LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.scene_local_inventory, container, false);
        ContentLayout contentLayout = (ContentLayout) ViewUtils.$$(view, R.id.content_layout);
        mRecyclerView = contentLayout.getRecyclerView();

        Context context = getEHContext();
        if (context == null) return view;
        Resources resources = context.getResources();

        mAdapter = new InventoryAdapter();
        mAdapter.setHasStableIds(true);
        mRecyclerView.setAdapter(mAdapter);

        AutoStaggeredGridLayoutManager layoutManager = new AutoStaggeredGridLayoutManager(
                0, StaggeredGridLayoutManager.VERTICAL);
        layoutManager.setColumnSize(resources.getDimensionPixelOffset(Settings.getDetailSizeResId()));
        layoutManager.setStrategy(AutoStaggeredGridLayoutManager.STRATEGY_MIN_SIZE);
        mRecyclerView.setLayoutManager(layoutManager);
        mRecyclerView.setSelector(Ripple.generateRippleDrawable(
                context,
                !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme),
                new ColorDrawable(Color.TRANSPARENT)));
        mRecyclerView.setDrawSelectorOnTop(true);
        mRecyclerView.setClipToPadding(false);
        mRecyclerView.setOnItemClickListener(this);
        mRecyclerView.setOnItemLongClickListener(this);
        mRecyclerView.setChoiceMode(EasyRecyclerView.CHOICE_MODE_MULTIPLE_CUSTOM);
        mRecyclerView.setCustomCheckedListener(this);

        int interval = resources.getDimensionPixelOffset(R.dimen.gallery_list_interval);
        int paddingH = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_h);
        int paddingV = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_v);
        MarginItemDecoration decoration = new MarginItemDecoration(interval, paddingH, paddingV, paddingH, paddingV);
        mRecyclerView.addItemDecoration(decoration);
        decoration.applyPaddings(mRecyclerView);

        mHelper = new InventoryHelper();
        mHelper.setEmptyString(getEmptyString());
        contentLayout.setHelper(mHelper);

        // Group the list actions (refresh / go to page / download tasks / sort) into one expandable
        // FAB, the same way the online gallery list does (com.hippo.widget.FabLayout: last child is
        // the primary, the rest are secondary actions shown when expanded).
        mFabLayout = (FabLayout) ViewUtils.$$(view, R.id.fab_layout);
        mFabLayout.setAutoCancel(true);
        mFabLayout.setExpanded(false, false);
        mFabLayout.setHidePrimaryFab(false);
        mFabLayout.setOnClickFabListener(this);

        // Only the first time. On return from a detail the ContentLayout restores its data and scroll
        // position from saved view state, exactly like the online gallery list.
        if (!mHasFirstRefresh) {
            mHasFirstRefresh = true;
            mHelper.firstRefresh();
        }
        return view;
    }

    @NonNull
    private String getEmptyString() {
        if (!SmbStorage.isConfigured() || !Settings.getSmbSaveEnabled()) {
            return getString(R.string.local_inventory_disabled);
        }
        return getString(R.string.local_inventory_empty);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setTitle(R.string.local_inventory);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
    }

    @Override
    public void onClickPrimaryFab(FabLayout view, FloatingActionButton fab) {
        view.toggle();
    }

    @Override
    public void onClickSecondaryFab(FabLayout view, FloatingActionButton fab, int position) {
        switch (position) {
            case FAB_SORT:
                showSortDialog();
                break;
            case FAB_GO_TO:
                showGoToDialog();
                break;
            case FAB_REFRESH:
                if (mHelper != null) {
                    mHelper.refresh();
                }
                break;
            case FAB_RESYNC_SELECTED:
                showResyncDialog(selectedGalleries());
                break;
            case FAB_DELETE_SELECTED:
                confirmDelete(selectedGalleries());
                break;
            case FAB_SELECT_ALL:
                if (mRecyclerView != null) {
                    mRecyclerView.checkAll();
                }
                break;
        }
        view.setExpanded(false);
    }

    @Override
    public void onBackPressed() {
        if (mFabLayout != null && mFabLayout.isExpanded()) {
            mFabLayout.setExpanded(false);
            return;
        }
        if (mRecyclerView != null && mRecyclerView.isInCustomChoice()) {
            mRecyclerView.outOfCustomChoiceMode();
            return;
        }
        super.onBackPressed();
    }

    private void showGoToDialog() {
        Context context = getEHContext();
        if (context == null || mHelper == null) {
            return;
        }
        int pages = mHelper.getPages();
        if (pages <= 0 || !mHelper.canGoTo()) {
            return;
        }
        GoToDialogHelper helper = new GoToDialogHelper(pages, mHelper.getPageForTop());
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.go_to)
                .setView(R.layout.dialog_go_to)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.show();
        helper.setDialog(dialog);
    }

    @Override
    public void onNavigationClick(View view) {
        onBackPressed();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mHelper != null) {
            // Drop the favourite-status listener registered by GalleryInfoContentHelper. If the share
            // is currently empty, allow a fresh first refresh next time the view is created.
            if (1 == mHelper.getShownViewIndex()) {
                mHasFirstRefresh = false;
            }
            mHelper.destroy();
            mHelper = null;
        }
        if (mRecyclerView != null) {
            mRecyclerView.stopScroll();
            mRecyclerView = null;
        }
        mAdapter = null;
        mFabLayout = null;
    }

    private void showSortDialog() {
        Context context = getEHContext();
        if (context == null) {
            return;
        }
        int current = Settings.getLocalInventorySort();
        new AlertDialog.Builder(context)
                .setTitle(R.string.local_inventory_sort_title)
                .setSingleChoiceItems(R.array.local_inventory_sort, current, (dialog, which) -> {
                    if (which != Settings.getLocalInventorySort()) {
                        Settings.putLocalInventorySort(which);
                        if (mHelper != null) {
                            mHelper.refresh();
                        }
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public boolean onItemClick(EasyRecyclerView parent, View view, int position, long id) {
        if (mHelper == null) {
            return false;
        }
        if (mRecyclerView != null && mRecyclerView.isInCustomChoice()) {
            mRecyclerView.toggleItemChecked(position);
            return true;
        }
        GalleryInfo gi = mHelper.getDataAtEx(position);
        if (gi == null) {
            return false;
        }
        openDetail(gi);
        return true;
    }

    /**
     * Long press starts selecting, the same as the download list and favourites.
     *
     * <p>It used to open a menu for the one gallery. Selection replaces it rather than joining it:
     * every action the menu offered works on a set just as well, and one gesture that means
     * different things on different screens is worse than one extra tap.
     */
    @Override
    public boolean onItemLongClick(EasyRecyclerView parent, View view, int position, long id) {
        if (mRecyclerView == null || mHelper == null) {
            return false;
        }
        if (!mRecyclerView.isInCustomChoice()) {
            mRecyclerView.intoCustomChoiceMode();
        }
        mRecyclerView.toggleItemChecked(position);
        return true;
    }

    // ---------- selection ----------

    @Override
    public void onIntoCustomChoice(EasyRecyclerView view) {
        showSelectionFabs();
        // A list that reloads under a selection loses it, and the pull-to-refresh gesture is easy
        // to trigger while reaching for a card.
        if (mHelper != null) {
            mHelper.setRefreshLayoutEnable(false);
        }
    }

    @Override
    public void onOutOfCustomChoice(EasyRecyclerView view) {
        showNormalFabs();
        if (mHelper != null) {
            mHelper.setRefreshLayoutEnable(true);
        }
    }

    @Override
    public void onItemCheckedStateChanged(EasyRecyclerView view, int position, long id, boolean checked) {
        if (view.getCheckedItemCount() == 0) {
            view.outOfCustomChoiceMode();
        }
    }

    private void setFabs(boolean selecting) {
        if (mFabLayout == null) {
            return;
        }
        mFabLayout.setSecondaryFabVisibilityAt(FAB_SORT, !selecting);
        mFabLayout.setSecondaryFabVisibilityAt(FAB_GO_TO, !selecting);
        mFabLayout.setSecondaryFabVisibilityAt(FAB_REFRESH, !selecting);
        mFabLayout.setSecondaryFabVisibilityAt(FAB_RESYNC_SELECTED, selecting);
        mFabLayout.setSecondaryFabVisibilityAt(FAB_DELETE_SELECTED, selecting);
        mFabLayout.setSecondaryFabVisibilityAt(FAB_SELECT_ALL, selecting);
    }

    private final Runnable mShowNormalFabsRunnable = () -> setFabs(false);

    /**
     * Delayed, copying the favourites screen: leaving selection mode animates the buttons out, and
     * swapping the set underneath that animation makes them flicker.
     */
    private void showNormalFabs() {
        SimpleHandler.getInstance().removeCallbacks(mShowNormalFabsRunnable);
        SimpleHandler.getInstance().postDelayed(mShowNormalFabsRunnable, 300);
    }

    private void showSelectionFabs() {
        SimpleHandler.getInstance().removeCallbacks(mShowNormalFabsRunnable);
        setFabs(true);
    }

    /** The galleries ticked right now, in list order. */
    @NonNull
    private List<GalleryInfo> selectedGalleries() {
        List<GalleryInfo> out = new ArrayList<>();
        if (mRecyclerView == null || mHelper == null) {
            return out;
        }
        android.util.SparseBooleanArray checked = mRecyclerView.getCheckedItemPositions();
        for (int i = 0, n = checked.size(); i < n; i++) {
            if (!checked.valueAt(i)) {
                continue;
            }
            GalleryInfo gi = mHelper.getDataAtEx(checked.keyAt(i));
            if (gi != null) {
                out.add(gi);
            }
        }
        return out;
    }

    private void leaveSelection() {
        if (mRecyclerView != null && mRecyclerView.isInCustomChoice()) {
            mRecyclerView.outOfCustomChoiceMode();
        }
    }

    // ---------- re-sync with e-hentai (#16) ----------

    private static final int RESYNC_METADATA = 0;
    private static final int RESYNC_PAGES = 1;
    private static final int RESYNC_BOTH = 2;

    /**
     * Asks what to bring back down: the record, the pages, or both.
     *
     * <p>The caveat about the title rides on the two items it applies to rather than sitting in a
     * message above them: an AlertDialog shows a message or a list, never both, and setting one
     * silently drops the other.
     *
     * <p>Two separate things wear the same word. The record is a single fetch that rewrites
     * {@code metadata.json}; the pages are a download that may run for minutes. Someone whose tags
     * are out of date does not want to re-download anything, and someone with a gallery full of
     * holes does not care about tags — so they are offered apart rather than bundled.
     */
    private void showResyncDialog(@NonNull List<GalleryInfo> galleries) {
        Context context = getEHContext();
        if (context == null || galleries.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.local_inventory_resync_title)
                .setItems(R.array.local_inventory_resync_mode, (dialog, which) -> {
                    if (which == RESYNC_METADATA || which == RESYNC_BOTH) {
                        resyncMetadata(galleries);
                    }
                    if (which == RESYNC_PAGES || which == RESYNC_BOTH) {
                        repairMissingPages(galleries);
                    }
                    leaveSelection();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * One fetch and one write per gallery, one after another.
     *
     * <p>Serial rather than parallel because the other end is e-hentai and a fan-out of detail
     * requests is how an account meets a rate limit. Each row is swapped in as it lands, so a long
     * run visibly progresses instead of sitting still and then jumping.
     */
    private void resyncMetadata(@NonNull List<GalleryInfo> galleries) {
        Context context = getEHContext();
        if (context == null || galleries.isEmpty()) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        final List<GalleryInfo> batch = new ArrayList<>(galleries);
        Toast.makeText(appContext, R.string.local_inventory_resync_running, Toast.LENGTH_SHORT).show();
        Runnable task = () -> {
            int updated = 0;
            for (GalleryInfo gi : batch) {
                final GalleryInfo fresh = SmbMetadata.resyncMetadata(appContext, gi);
                if (fresh != null) {
                    updated++;
                    SimpleHandler.getInstance().post(() -> replaceRow(fresh));
                }
            }
            final int done = updated;
            SimpleHandler.getInstance().post(() -> {
                if (batch.size() == 1) {
                    // Distinguished from success on purpose: the old path wrote the unchanged
                    // record back when the fetch failed, so the two looked identical.
                    Toast.makeText(appContext,
                            done == 1 ? R.string.local_inventory_resync_done
                                      : R.string.local_inventory_resync_failed,
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(appContext, getString(
                            R.string.local_inventory_resync_many_done, done, batch.size()),
                            Toast.LENGTH_LONG).show();
                }
            });
        };
        if (mExecutor != null) {
            mExecutor.execute(task);
        } else {
            new Thread(task, "LocalInventoryResync").start();
        }
    }

    /**
     * Puts the gallery back in the download queue, which fetches only what is missing.
     *
     * <p>No separate repair path and no completeness check: in download mode {@code contain()} asks
     * the share, so every page already there is skipped. A gallery that turns out to be complete
     * finishes immediately.
     */
    private void repairMissingPages(@NonNull List<GalleryInfo> galleries) {
        Context context = getEHContext();
        if (context == null || galleries.isEmpty()) {
            return;
        }
        for (GalleryInfo gi : galleries) {
            SmbDirectDownloader.getInstance().start(context, gi);
        }
        Toast.makeText(context.getApplicationContext(), R.string.local_inventory_resync_queued,
                Toast.LENGTH_SHORT).show();
    }

    /** Main thread. Swaps a re-synced record into the row it came from, by gid. */
    private void replaceRow(@NonNull GalleryInfo fresh) {
        if (mHelper == null) {
            return;
        }
        for (int i = 0, n = mHelper.size(); i < n; i++) {
            GalleryInfo at = mHelper.getDataAt(i);
            if (at != null && at.gid == fresh.gid) {
                mHelper.replaceAt(i, fresh);
                break;
            }
        }
    }

    /** Deleting the on-share folder cannot be undone, so it always goes through a confirmation. */
    private void confirmDelete(@NonNull List<GalleryInfo> galleries) {
        Context context = getEHContext();
        if (context == null || galleries.isEmpty()) {
            return;
        }
        // One gallery is named; a set is counted. Listing twenty titles in a dialog is not a
        // clearer warning than the number, and the number is the part that should give pause.
        boolean one = galleries.size() == 1;
        String title = one
                ? getString(R.string.local_inventory_delete_confirm_title)
                : getString(R.string.local_inventory_delete_confirm_title_many, galleries.size());
        String message = one
                ? getString(R.string.local_inventory_delete_confirm_message,
                        EhUtils.getSuitableTitle(galleries.get(0)))
                : getString(R.string.local_inventory_delete_confirm_many, galleries.size());
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.local_inventory_delete, (d, w) -> deleteGalleries(galleries))
                .show();
    }

    /**
     * Erases each gallery's folder, one after another, and drops the rows that really went.
     *
     * <p>Serial, and each result is honoured separately: a folder that could not be deleted keeps
     * its row, because dropping it would claim a deletion that did not happen and the gallery
     * would simply be back on the next refresh.
     */
    private void deleteGalleries(@NonNull List<GalleryInfo> galleries) {
        Context context = getEHContext();
        if (context == null || galleries.isEmpty()) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        final List<GalleryInfo> batch = new ArrayList<>(galleries);
        leaveSelection();

        // A download in flight owns the folder: it holds a SpiderQueen that keeps writing pages
        // into it, so deleting underneath would just let it reappear half-populated. Cancelling
        // releases the queen and wipes the folder itself, which is the same end state.
        final List<GalleryInfo> toErase = new ArrayList<>();
        for (GalleryInfo gi : batch) {
            if (isBeingDownloaded(gi.gid)) {
                SmbDirectDownloader.getInstance().cancel(gi.gid);
                onGalleryDeleted(appContext, gi);
            } else {
                toErase.add(gi);
            }
        }
        if (toErase.isEmpty()) {
            return;
        }

        Runnable task = () -> {
            final List<GalleryInfo> gone = new ArrayList<>();
            for (GalleryInfo gi : toErase) {
                if (SmbStorage.deleteGalleryFolder(gi)) {
                    gone.add(gi);
                }
            }
            SimpleHandler.getInstance().post(() -> {
                for (GalleryInfo gi : gone) {
                    onGalleryDeleted(appContext, gi);
                }
                if (gone.size() == toErase.size()) {
                    if (toErase.size() > 1) {
                        Toast.makeText(appContext, getString(
                                R.string.local_inventory_delete_many_done, gone.size(), toErase.size()),
                                Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                Toast.makeText(appContext,
                        toErase.size() == 1
                                ? getString(R.string.local_inventory_delete_failed)
                                : getString(R.string.local_inventory_delete_many_done,
                                        gone.size(), toErase.size()),
                        Toast.LENGTH_LONG).show();
            });
        };
        if (mExecutor != null) {
            mExecutor.execute(task);
        } else {
            new Thread(task, "LocalInventoryDelete").start();
        }
    }

    private static boolean isBeingDownloaded(long gid) {
        for (SmbDirectDownloader.TaskSnapshot t : SmbDirectDownloader.getInstance().snapshotTasks()) {
            if (t.gid == gid) {
                return true;
            }
        }
        return false;
    }

    /** Main thread. Drops every trace of a gallery that is no longer on the share. */
    private void onGalleryDeleted(@NonNull Context appContext, @NonNull GalleryInfo gi) {
        // Reads for this gid must stop being routed to SMB now that nothing is there.
        SmbStorage.unmarkGidAsSmbTarget(gi.gid);
        // Local leftovers that would otherwise be served for a gallery that no longer exists.
        SmbPreviewCache.evictGallery(gi.gid);
        try {
            EhApplication.getConaco(appContext).getBeerBelly()
                    .remove(EhCacheKeyFactory.getThumbKey(gi.gid));
        } catch (Throwable ignored) {
            // A stale cover is cosmetic; never let it fail the delete.
        }

        if (mHelper == null) {
            return;
        }
        // Look the row up by gid rather than trusting the position captured before the dialogs:
        // a refresh may have landed in between.
        for (int i = 0, n = mHelper.size(); i < n; i++) {
            GalleryInfo at = mHelper.getDataAt(i);
            if (at != null && at.gid == gi.gid) {
                mHelper.removeAt(i);
                break;
            }
        }
        // The paging cache is only rebuilt on refresh, so a ref left behind here would come back as
        // a row with no readable metadata the next time that page is fetched.
        mHelper.forgetRef(SmbPaths.buildGalleryFolderName(gi));
    }

    private void openDetail(@Nullable GalleryInfo gi) {
        if (gi == null) {
            return;
        }
        Bundle args = new Bundle();
        args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GALLERY_INFO);
        args.putParcelable(GalleryDetailScene.KEY_GALLERY_INFO, gi);
        // Render fully from local SMB metadata. Reconstructs tags from tgList so the
        // detail page does not need a network call.
        args.putParcelable(GalleryDetailScene.KEY_GALLERY_DETAIL, SmbMetadata.buildOfflineDetail(gi));
        // SMB metadata never carries comments — hide that section entirely.
        args.putBoolean(GalleryDetailScene.KEY_HIDE_COMMENTS, true);
        startScene(new Announcer(GalleryDetailScene.class).setArgs(args));

        // Older entries may have been written before tag enrichment was added.
        // Opportunistically fetch detail in the background and rewrite metadata so the
        // next open is also fully offline.
        Context context = getEHContext();
        if (context != null && (gi.tgList == null || gi.tgList.isEmpty())) {
            SmbMetadata.enrichLocalMetadataIfMissing(context, gi);
        }
    }

    private void openReader(@Nullable GalleryInfo gi) {
        if (gi == null) {
            return;
        }
        Context context = getEHContext();
        if (context == null) {
            return;
        }
        // Mark the gid so SpiderDen routes reads (cover/spider info/pages) to SMB instead
        // of looking on phone storage.
        SmbStorage.markGidAsSmbTarget(gi.gid);
        Intent intent = new Intent(context, GalleryActivity.class);
        intent.setAction(GalleryActivity.ACTION_EH);
        intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, gi);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private final class InventoryHolder extends RecyclerView.ViewHolder {
        final LoadImageView thumb;
        final TextView title;
        final TextView uploader;
        final SimpleRatingView rating;
        final TextView category;
        final TextView posted;
        final TextView simpleLanguage;
        final TextView pages;
        final SmbStatusBadge smbBadge;

        InventoryHolder(View itemView) {
            super(itemView);
            thumb = (LoadImageView) itemView.findViewById(R.id.thumb);
            title = (TextView) itemView.findViewById(R.id.title);
            uploader = (TextView) itemView.findViewById(R.id.uploader);
            rating = (SimpleRatingView) itemView.findViewById(R.id.rating);
            category = (TextView) itemView.findViewById(R.id.category);
            posted = (TextView) itemView.findViewById(R.id.posted);
            simpleLanguage = (TextView) itemView.findViewById(R.id.simple_language);
            pages = (TextView) itemView.findViewById(R.id.pages);
            smbBadge = itemView.findViewById(R.id.smb_badge);
        }
    }

    private final class InventoryAdapter extends RecyclerView.Adapter<InventoryHolder> {
        @Override
        public long getItemId(int position) {
            GalleryInfo gi = mHelper != null ? mHelper.getDataAtEx(position) : null;
            return gi != null ? gi.gid : RecyclerView.NO_ID;
        }

        @Override
        public InventoryHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = getLayoutInflater2().inflate(R.layout.item_gallery_list, parent, false);
            return new InventoryHolder(v);
        }

        @Override
        public void onBindViewHolder(InventoryHolder holder, int position) {
            GalleryInfo gi = mHelper != null ? mHelper.getDataAtEx(position) : null;
            if (gi != null) {
                bind(holder, gi);
            }
        }

        /**
         * A progress update touches the badge and nothing else. Without this the card would be
         * rebuilt from scratch every couple of seconds, cover load and all.
         */
        @Override
        public void onBindViewHolder(@NonNull InventoryHolder holder, int position,
                @NonNull List<Object> payloads) {
            if (payloads.contains(PAYLOAD_BADGE)) {
                GalleryInfo gi = mHelper != null ? mHelper.getDataAtEx(position) : null;
                if (gi != null) {
                    bindDownloadingBadge(holder, gi.gid);
                }
                return;
            }
            super.onBindViewHolder(holder, position, payloads);
        }

        @Override
        public int getItemCount() {
            return mHelper != null ? mHelper.size() : 0;
        }
    }

    private void bind(@NonNull InventoryHolder holder, @NonNull GalleryInfo gi) {
        // Route the cover load through SmbCoverDataContainer so Conaco reads cover.<ext>
        // straight from the SMB share (saved alongside the gallery at download time)
        // instead of hitting e-hentai for the thumbnail URL. useNetwork=false makes the
        // load offline-only — if the on-share cover is missing the cell just stays empty
        // rather than silently leaking out to the network.
        holder.thumb.load(EhCacheKeyFactory.getThumbKey(gi.gid),
                gi.thumb != null ? gi.thumb : ("smb-cover://" + gi.gid),
                new SmbCoverDataContainer(gi.gid, gi.title), false, false);
        // Tap the thumbnail to jump straight into the reader (offline-friendly path).
        // Tapping anywhere else on the card opens the gallery detail page (handled by the
        // RecyclerView's OnItemClickListener).
        holder.thumb.setOnClickListener(v -> {
            // The thumb has its own tap target, so it would sail past the list's choice handling
            // and drop the user into the reader mid-selection.
            if (mRecyclerView != null && mRecyclerView.isInCustomChoice()) {
                mRecyclerView.toggleItemChecked(holder.getBindingAdapterPosition());
                return;
            }
            openReader(gi);
        });
        holder.title.setText(EhUtils.getSuitableTitle(gi));
        holder.uploader.setText(gi.uploader);
        holder.rating.setRating(gi.rating);
        String catText = EhUtils.getCategory(gi.category);
        holder.category.setText(catText);
        holder.category.setBackgroundColor(EhUtils.getCategoryColor(gi.category));
        holder.posted.setText(gi.posted);
        holder.simpleLanguage.setText(gi.simpleLanguage);
        if (gi.pages > 0) {
            holder.pages.setText(getResources().getQuantityString(R.plurals.page_count, gi.pages, gi.pages));
        } else {
            holder.pages.setText(null);
        }
        bindDownloadingBadge(holder, gi.gid);
    }

    /**
     * Marks a gallery still being written to the share: how far along, in the colour of the device
     * writing it (#77). Without it a half-downloaded gallery is indistinguishable from a complete
     * one, and the user only finds out by opening it and hitting missing pages.
     *
     * <p>This device's own downloads are marked too, in this device's own colour: one rule, and the
     * colour is what says whose it is.
     */
    private void bindDownloadingBadge(@NonNull InventoryHolder holder, long gid) {
        DownloadMark mark = mDownloadMarks.get(gid);
        if (mark == null) {
            holder.smbBadge.setVisibility(View.GONE);
            return;
        }
        holder.smbBadge.setProgress(SmbDeviceColor.of(mark.clientId), mark.progress);
        holder.smbBadge.setVisibility(View.VISIBLE);
    }

    /** One page's galleries plus the total page count, computed off the main thread. */
    private static final class PageResult {
        @NonNull final List<GalleryInfo> data;
        final int pages;

        PageResult(@NonNull List<GalleryInfo> data, int pages) {
            this.data = data;
            this.pages = pages;
        }
    }

    /** The full display ordering, computed once per refresh and sliced per page. */
    private static final class Ordering {
        @NonNull final List<SmbStorage.GalleryRef> refs;
        // null => date sort: each ref's metadata is read on demand for its page.
        // non-null => sort needed every folder's metadata to order, so it's all cached here.
        @Nullable final Map<String, GalleryInfo> infos;

        Ordering(@NonNull List<SmbStorage.GalleryRef> refs, @Nullable Map<String, GalleryInfo> infos) {
            this.refs = refs;
            this.infos = infos;
        }
    }

    private final class InventoryHelper extends GalleryInfoContentHelper {

        // Cached across page fetches so paging doesn't re-list the share every page. Rebuilt on
        // refresh. volatile because it's assigned/read from the load executor.
        @Nullable
        private volatile Ordering mOrdering;

        @Override
        protected void getPageData(int taskId, int type, int page) {
            // Date sort can order from the cheap listing alone; rebuild the ordering on a refresh or
            // when we have none yet (e.g. paging after the view was recreated).
            final boolean rebuild = type == TYPE_REFRESH || mOrdering == null;
            final SmbSortMode mode = SmbSortMode.fromOrdinal(Settings.getLocalInventorySort());
            Runnable task = () -> {
                final PageResult result;
                try {
                    // Issue #2644 requires a hard 7s cap — if the share can't be reached, surface the
                    // error state instead of hanging. jcifs's socket timeouts can't be bounded without
                    // rebuilding the global SingletonContext, so wrap the read in a Future.
                    ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "smb-inventory-page");
                        t.setDaemon(true);
                        return t;
                    });
                    Future<PageResult> fut = pool.submit(() -> loadPage(mode, page, rebuild));
                    try {
                        result = fut.get(7, TimeUnit.SECONDS);
                    } catch (TimeoutException te) {
                        fut.cancel(true);
                        throw new IOException(EhApplication.getInstance()
                                .getString(R.string.local_inventory_timeout));
                    } finally {
                        pool.shutdownNow();
                    }
                } catch (Throwable e) {
                    final Exception ex = e instanceof Exception ? (Exception) e : new Exception(e);
                    SimpleHandler.getInstance().post(() -> {
                        if (isCurrentTask(taskId)) {
                            onGetException(taskId, ex);
                        }
                    });
                    return;
                }
                SimpleHandler.getInstance().post(() -> {
                    if (!isCurrentTask(taskId)) {
                        return;
                    }
                    // Mark every gid on the page so cover/detail/reader reads route through SMB.
                    for (GalleryInfo gi : result.data) {
                        SmbStorage.markGidAsSmbTarget(gi.gid);
                    }
                    onGetPageData(taskId, result.pages, page + 1, result.data);
                    // After the page is on screen, not before it: a badge is worth a redraw, never
                    // worth making the page wait on a second trip to the share.
                    refreshDownloadingBadges();
                });
            };
            if (mExecutor != null) {
                mExecutor.execute(task);
            } else {
                new Thread(task, "LocalInventoryLoader").start();
            }
        }

        @Override
        protected void getPageData(int taskId, int type, int page, String append) {
            getPageData(taskId, type, page);
        }

        @Override
        protected void getExPageData(int pageAction, int taskId, int page) {
            // Inventory paging is plain page-index based (no e-hentai prev/next hrefs), so this is the
            // same fetch as the normal path.
            getPageData(taskId, pageAction, page);
        }

        @NonNull
        private PageResult loadPage(@NonNull SmbSortMode mode, int page, boolean rebuild) {
            Ordering ordering = mOrdering;
            if (rebuild || ordering == null) {
                ordering = buildOrdering(mode);
                mOrdering = ordering;
            }
            List<SmbStorage.GalleryRef> refs = ordering.refs;
            int total = refs.size();
            int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
            List<GalleryInfo> data = new ArrayList<>();
            int from = page * PAGE_SIZE;
            int to = Math.min(from + PAGE_SIZE, total);
            for (int i = from; i < to; i++) {
                SmbStorage.GalleryRef ref = refs.get(i);
                GalleryInfo gi = ordering.infos != null
                        ? ordering.infos.get(ref.folderName)
                        : SmbStorage.readGalleryInfo(ref);
                if (gi != null) {
                    data.add(gi);
                }
            }
            return new PageResult(data, pages);
        }

        /**
         * Drops one folder from the cached ordering after it has been deleted from the share.
         *
         * <p>Replaces the {@link Ordering} rather than mutating it: {@link #mOrdering} is read from
         * the load executor, and a page fetch may be walking the very list this is called on.
         */
        void forgetRef(@NonNull String folderName) {
            Ordering current = mOrdering;
            if (current == null) {
                return;
            }
            List<SmbStorage.GalleryRef> refs = new ArrayList<>(current.refs.size());
            boolean removed = false;
            for (SmbStorage.GalleryRef ref : current.refs) {
                if (!removed && ref.folderName.equals(folderName)) {
                    removed = true;
                    continue;
                }
                refs.add(ref);
            }
            if (!removed) {
                return;
            }
            Map<String, GalleryInfo> infos = null;
            if (current.infos != null) {
                infos = new HashMap<>(current.infos);
                infos.remove(folderName);
            }
            mOrdering = new Ordering(refs, infos);
        }

        @NonNull
        private Ordering buildOrdering(@NonNull SmbSortMode mode) {
            if (mode == SmbSortMode.DOWNLOAD_DATE_DESC) {
                // Recently-downloaded order keys off the folder mtime the listing already carries, so
                // no metadata is read until a page needs it.
                List<SmbStorage.GalleryRef> refs = SmbStorage.listGalleryRefs();
                Collections.sort(refs, (a, b) -> Long.compare(b.folderMtime, a.folderMtime));
                return new Ordering(refs, null);
            }
            // Other sorts need fields that only live in metadata.json, so the whole share has to be
            // read to order it; cache it and serve pages from the cache.
            List<GalleryInfo> loaded = SmbStorage.loadInventory(mode);
            List<SmbStorage.GalleryRef> refs = new ArrayList<>(loaded.size());
            Map<String, GalleryInfo> infos = new HashMap<>();
            for (GalleryInfo gi : loaded) {
                String folderName = SmbPaths.buildGalleryFolderName(gi);
                refs.add(new SmbStorage.GalleryRef(folderName, 0L));
                infos.put(folderName, gi);
            }
            return new Ordering(refs, infos);
        }

        @Override
        protected Context getContext() {
            return LocalInventoryScene.this.getEHContext();
        }

        @Override
        protected void notifyDataSetChanged() {
            if (mAdapter != null) {
                mAdapter.notifyDataSetChanged();
            }
        }

        @Override
        protected void notifyItemRangeChanged(int positionStart, int itemCount) {
            if (mAdapter != null) {
                mAdapter.notifyItemRangeChanged(positionStart, itemCount);
            }
        }

        @Override
        protected void notifyItemRangeRemoved(int positionStart, int itemCount) {
            if (mAdapter != null) {
                mAdapter.notifyItemRangeRemoved(positionStart, itemCount);
            }
        }

        @Override
        protected void notifyItemRangeInserted(int positionStart, int itemCount) {
            if (mAdapter != null) {
                mAdapter.notifyItemRangeInserted(positionStart, itemCount);
            }
        }

        @Override
        protected boolean isDuplicate(GalleryInfo d1, GalleryInfo d2) {
            return d1.gid == d2.gid;
        }
    }

    private class GoToDialogHelper implements View.OnClickListener,
            DialogInterface.OnDismissListener {

        private final int mPages;
        private final int mCurrentPage;

        @Nullable
        private Slider mSlider;
        @Nullable
        private Dialog mDialog;

        private GoToDialogHelper(int pages, int currentPage) {
            mPages = pages;
            mCurrentPage = currentPage;
        }

        public void setDialog(@NonNull AlertDialog dialog) {
            mDialog = dialog;
            ((TextView) ViewUtils.$$(dialog, R.id.start)).setText(String.format(Locale.US, "%d", 1));
            ((TextView) ViewUtils.$$(dialog, R.id.end)).setText(String.format(Locale.US, "%d", mPages));
            mSlider = (Slider) ViewUtils.$$(dialog, R.id.slider);
            mSlider.setRange(1, mPages);
            mSlider.setProgress(mCurrentPage + 1);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(this);
            dialog.setOnDismissListener(this);
        }

        @Override
        public void onClick(View v) {
            if (mSlider == null || mHelper == null) {
                return;
            }
            int page = mSlider.getProgress() - 1;
            if (page >= 0 && page < mPages) {
                mHelper.goTo(page);
                if (mDialog != null) {
                    mDialog.dismiss();
                    mDialog = null;
                }
            }
        }

        @Override
        public void onDismiss(DialogInterface dialog) {
            mDialog = null;
            mSlider = null;
        }
    }
}

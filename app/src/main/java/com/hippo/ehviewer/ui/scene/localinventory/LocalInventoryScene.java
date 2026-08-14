/*
 * Copyright 2024 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.localinventory;

import com.hippo.ehviewer.smb.SmbSpiderStorage;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
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
import com.hippo.drawerlayout.DrawerLayout;
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
import com.hippo.ehviewer.smb.SmbMetadata;
import com.hippo.ehviewer.smb.SmbPaths;
import com.hippo.ehviewer.smb.SmbSortMode;
import com.hippo.ehviewer.smb.SmbConnection;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.ehviewer.ui.scene.ToolbarScene;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Browses the share's galleries, paginated like the online list (ContentHelper); each page reads
 * only its own slice of metadata, so a big share never blocks on a full sweep.
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

    // ---------- collaborators (#99) ----------

    /** Runs on the app pool when there is one; the fallback thread keeps early calls working. */
    private final java.util.concurrent.Executor mWorker = task -> {
        if (mExecutor != null) {
            mExecutor.execute(task);
        } else {
            new Thread(task, "LocalInventory").start();
        }
    };

    private final InventoryPager mPager = new InventoryPager();

    private final InventoryBadges mBadges = new InventoryBadges(mWorker, this::applyDownloadMarks);

    private final InventoryOps mOps = new InventoryOps(mWorker, new InventoryOps.Listener() {
        @Override
        public void onRowResynced(@NonNull GalleryInfo fresh) {
            replaceRow(fresh);
        }

        @Override
        public void onResyncFinished(int done, int total) {
            Context context = getEHContext();
            if (context == null) {
                return;
            }
            Context appContext = context.getApplicationContext();
            if (total == 1) {
                // Distinguished from success on purpose: a failed fetch must not look like a no-op sync.
                Toast.makeText(appContext,
                        done == 1 ? R.string.local_inventory_resync_done
                                  : R.string.local_inventory_resync_failed,
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(appContext, getString(
                        R.string.local_inventory_resync_many_done, done, total),
                        Toast.LENGTH_LONG).show();
            }
        }

        @Override
        public void onGalleryDeleted(@NonNull GalleryInfo gi) {
            dropRow(gi);
        }

        @Override
        public void onDeleteFinished(int gone, int total) {
            Context context = getEHContext();
            if (context == null) {
                return;
            }
            Context appContext = context.getApplicationContext();
            if (gone == total) {
                if (total > 1) {
                    Toast.makeText(appContext, getString(
                            R.string.local_inventory_delete_many_done, gone, total),
                            Toast.LENGTH_SHORT).show();
                }
                return;
            }
            Toast.makeText(appContext,
                    total == 1
                            ? getString(R.string.local_inventory_delete_failed)
                            : getString(R.string.local_inventory_delete_many_done, gone, total),
                    Toast.LENGTH_LONG).show();
        }
    });

    /** Marks by gid, as last applied; kept for the per-row diff. */
    @NonNull
    private Map<Long, InventoryBadges.Mark> mDownloadMarks = Collections.emptyMap();

    /** Tells the adapter to redraw a card's badge and leave the rest of it alone. */
    private static final Object PAYLOAD_BADGE = new Object();

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
        mBadges.attach();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mBadges.detach();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Coming back from the reader or a detail page, where a download may have been started.
        mBadges.refresh();
    }

    /** Main thread. Payload-rebinds only changed cards — a full rebuild swallows long-presses. */
    private void applyDownloadMarks(@NonNull Map<Long, InventoryBadges.Mark> marks) {
        Map<Long, InventoryBadges.Mark> previous = mDownloadMarks;
        mDownloadMarks = marks;
        if (mAdapter == null || mHelper == null) {
            return;
        }
        for (int i = 0, n = mHelper.size(); i < n; i++) {
            GalleryInfo gi = mHelper.getDataAt(i);
            if (gi == null) {
                continue;
            }
            InventoryBadges.Mark before = previous.get(gi.gid);
            InventoryBadges.Mark after = marks.get(gi.gid);
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
        if (!SmbConnection.isConfigured() || !Settings.getSmbSaveEnabled()) {
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

    /** Long press selects, same as every other list. */
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
        if (mFabLayout != null) {
            // Tapping elsewhere must not take the actions away while a selection is still
            // standing. Auto-cancel is right for browsing, where the menu is transient.
            mFabLayout.setAutoCancel(false);
            // Posted, as on the favourites screen: the visibility swap above needs a layout pass
            // before the expansion has the right buttons to animate.
            SimpleHandler.getInstance().post(() -> {
                if (mFabLayout != null) {
                    mFabLayout.setExpanded(true);
                }
            });
        }
        // A list that reloads under a selection loses it, and the pull-to-refresh gesture is easy
        // to trigger while reaching for a card.
        if (mHelper != null) {
            mHelper.setRefreshLayoutEnable(false);
        }
        // An edge swipe towards a card at the margin would otherwise pull the navigation drawer
        // out from under the selection.
        setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT);
        setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT);
    }

    @Override
    public void onOutOfCustomChoice(EasyRecyclerView view) {
        showNormalFabs();
        if (mFabLayout != null) {
            mFabLayout.setAutoCancel(true);
            mFabLayout.setExpanded(false);
        }
        if (mHelper != null) {
            mHelper.setRefreshLayoutEnable(true);
        }
        setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.LEFT);
        setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT);
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
     * Record, pages, or both — offered apart (a metadata fetch and a minutes-long download are
     * different asks). AlertDialog shows message OR list, so the caveat rides on the items.
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

    private void resyncMetadata(@NonNull List<GalleryInfo> galleries) {
        Context context = getEHContext();
        if (context == null || galleries.isEmpty()) {
            return;
        }
        Context appContext = context.getApplicationContext();
        Toast.makeText(appContext, R.string.local_inventory_resync_running, Toast.LENGTH_SHORT).show();
        mOps.resyncMetadata(appContext, galleries);
    }

    private void repairMissingPages(@NonNull List<GalleryInfo> galleries) {
        Context context = getEHContext();
        if (context == null || galleries.isEmpty()) {
            return;
        }
        mOps.repairMissingPages(context, galleries);
        Toast.makeText(context.getApplicationContext(), R.string.local_inventory_resync_queued,
                Toast.LENGTH_SHORT).show();
    }

    /** Main thread. Swaps the fresh record in by gid; the paging cache follows the rename (#86). */
    private void replaceRow(@NonNull GalleryInfo fresh) {
        if (mHelper == null) {
            return;
        }
        for (int i = 0, n = mHelper.size(); i < n; i++) {
            GalleryInfo at = mHelper.getDataAt(i);
            if (at == null || at.gid != fresh.gid) {
                continue;
            }
            String before = SmbPaths.buildGalleryFolderName(at);
            String after = SmbPaths.buildGalleryFolderName(fresh);
            if (!before.equals(after)) {
                mPager.renameRef(before, after);
            }
            mHelper.replaceAt(i, fresh);
            break;
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

    private void deleteGalleries(@NonNull List<GalleryInfo> galleries) {
        Context context = getEHContext();
        if (context == null || galleries.isEmpty()) {
            return;
        }
        leaveSelection();
        mOps.deleteGalleries(context.getApplicationContext(), galleries);
    }

    /** Main thread. Drops the row and the paging ref of a gallery no longer on the share. */
    private void dropRow(@NonNull GalleryInfo gi) {
        if (mHelper == null) {
            return;
        }
        // By gid, not a captured position: a refresh may have landed since the dialogs.
        for (int i = 0, n = mHelper.size(); i < n; i++) {
            GalleryInfo at = mHelper.getDataAt(i);
            if (at != null && at.gid == gi.gid) {
                mHelper.removeAt(i);
                break;
            }
        }
        mPager.forgetRef(SmbPaths.buildGalleryFolderName(gi));
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
        SmbSpiderStorage.markGidAsSmbTarget(gi.gid);
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

    /** Progress ring in the writing device's colour (#77); own downloads too, one rule. */
    private void bindDownloadingBadge(@NonNull InventoryHolder holder, long gid) {
        InventoryBadges.Mark mark = mDownloadMarks.get(gid);
        if (mark == null) {
            holder.smbBadge.setVisibility(View.GONE);
            return;
        }
        holder.smbBadge.setProgress(SmbDeviceColor.of(mark.clientId), mark.progress);
        holder.smbBadge.setVisibility(View.VISIBLE);
    }

    private final class InventoryHelper extends GalleryInfoContentHelper {

        @Override
        protected void getPageData(int taskId, int type, int page) {
            // Date sort can order from the cheap listing alone; rebuild on refresh or first use.
            final boolean rebuild = type == TYPE_REFRESH;
            final SmbSortMode mode = SmbSortMode.fromOrdinal(Settings.getLocalInventorySort());
            mWorker.execute(() -> {
                final InventoryPager.Page result;
                try {
                    result = mPager.loadPageBounded(mode, page, rebuild);
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
                        SmbSpiderStorage.markGidAsSmbTarget(gi.gid);
                    }
                    onGetPageData(taskId, result.pages, page + 1, result.data);
                    // After the page is on screen: a badge is never worth making the page wait.
                    mBadges.refresh();
                });
            });
        }

        @Override
        protected void getPageData(int taskId, int type, int page, String append) {
            getPageData(taskId, type, page);
        }

        @Override
        protected void getExPageData(int pageAction, int taskId, int page) {
            // Plain page-index paging; same fetch as the normal path.
            getPageData(taskId, pageAction, page);
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

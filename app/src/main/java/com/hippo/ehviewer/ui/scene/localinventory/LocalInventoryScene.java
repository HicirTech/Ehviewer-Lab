/*
 * Copyright 2024 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.localinventory;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
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
import com.hippo.ehviewer.storage.GalleryTargets;
import com.hippo.ehviewer.storage.InventoryQuery;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.ehviewer.storage.NetworkStorageSettings;
import com.hippo.ehviewer.storage.SortMode;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.ehviewer.ui.TagSelectorActivity;
import com.hippo.ehviewer.ui.scene.BaseScene;
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

/** Browses the galleries on the network share. */
public class LocalInventoryScene extends BaseScene
        implements EasyRecyclerView.OnItemClickListener, EasyRecyclerView.OnItemLongClickListener,
        FabLayout.OnClickFabListener, EasyRecyclerView.CustomChoiceListener, InventorySearchBar.Host {

    private static final String TAG = "LocalInventoryScene";

    private static final String KEY_SORT = "local_inventory_sort";

    /** TagSelectorActivity's result extra. */
    private static final String EXTRA_SELECTED_TAGS = "selected_tags";

    // Secondary FAB indexes, in scene_local_inventory.xml order.
    private static final int FAB_SORT = 0;
    private static final int FAB_GO_TO = 1;
    private static final int FAB_REFRESH = 2;
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
    @Nullable
    private InventorySearchBar mSearchBar;
    /** Null while the page lists the whole library. */
    @Nullable
    private InventorySearch mSearch;
    private boolean mHasFirstRefresh;
    @Nullable
    private ExecutorService mExecutor;

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
                            ? getString(R.string.local_inventory_delete_failed, NetworkStorage.active().displayName())
                            : getString(R.string.local_inventory_delete_many_done, gone, total),
                    Toast.LENGTH_LONG).show();
        }
    });

    private final ActivityResultLauncher<Intent> mTagSelectorLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Intent data = result.getData();
                String tags = result.getResultCode() == Activity.RESULT_OK && data != null
                        ? data.getStringExtra(EXTRA_SELECTED_TAGS) : null;
                if (mSearchBar != null && !TextUtils.isEmpty(tags)) {
                    mSearchBar.applyTags(tags);
                }
            });

    /** By gid, as last applied. */
    @NonNull
    private Map<Long, InventoryBadges.Mark> mDownloadMarks = Collections.emptyMap();

    private static final Object PAYLOAD_BADGE = new Object();

    /** Shown only while network storage is on, titled with the backend's name. */
    public static void updateDrawerItem(@Nullable NavigationView navView) {
        if (navView == null) {
            return;
        }
        MenuItem item = navView.getMenu().findItem(R.id.nav_local_inventory);
        item.setVisible(NetworkStorageSettings.isEnabled());
        item.setTitle(navView.getContext().getString(
                R.string.local_inventory, NetworkStorage.active().displayName()));
    }

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
        // A download may have started in the reader or a detail page.
        mBadges.refresh();
    }

    /** Rebinds only changed cards: a full rebuild swallows long-presses. */
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
    public View onCreateView2(LayoutInflater inflater,
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

        // Room for the search bar, as on the online list.
        int paddingTopSB = resources.getDimensionPixelOffset(R.dimen.gallery_padding_top_search_bar);
        mRecyclerView.setPadding(mRecyclerView.getPaddingLeft(), mRecyclerView.getPaddingTop() + paddingTopSB,
                mRecyclerView.getPaddingRight(), mRecyclerView.getPaddingBottom());
        View fastScroller = contentLayout.getFastScroller();
        fastScroller.setPadding(fastScroller.getPaddingLeft(), fastScroller.getPaddingTop() + paddingTopSB,
                fastScroller.getPaddingRight(), fastScroller.getPaddingBottom());
        contentLayout.getRefreshLayout().setHeaderTranslationY(paddingTopSB);

        mHelper = new InventoryHelper();
        mHelper.setEmptyString(getEmptyString());
        contentLayout.setHelper(mHelper);

        mFabLayout = (FabLayout) ViewUtils.$$(view, R.id.fab_layout);
        mFabLayout.setAutoCancel(true);
        mFabLayout.setExpanded(false, false);
        mFabLayout.setHidePrimaryFab(false);
        mFabLayout.setOnClickFabListener(this);
        addAboveSnackView(mFabLayout);

        mSearchBar = new InventorySearchBar(this, view, contentLayout, mFabLayout);
        mSearchBar.showSearch(mSearch);

        // On return from a detail the ContentLayout restores its own data.
        if (!mHasFirstRefresh) {
            mHasFirstRefresh = true;
            mHelper.firstRefresh();
        }
        return view;
    }

    @NonNull
    private String getEmptyString() {
        if (!NetworkStorage.active().isConfigured() || !NetworkStorageSettings.isEnabled()) {
            return getString(R.string.local_inventory_disabled);
        }
        if (mSearch != null) {
            return getString(R.string.local_inventory_search_empty, NetworkStorage.active().displayName());
        }
        return getString(R.string.local_inventory_empty, NetworkStorage.active().displayName());
    }

    @Override
    public void onSearch(@Nullable InventoryQuery query, @NonNull String keyword) {
        if (mHelper == null) {
            return;
        }
        // The refresh below moves every row, so a selection would point at other galleries.
        leaveSelection();
        mSearch = query == null ? null : new InventorySearch(query, keyword);
        mHelper.setEmptyString(getEmptyString());
        if (mSearchBar != null) {
            mSearchBar.showSearch(mSearch);
        }
        mHelper.refresh();
    }

    @Override
    public void openTagSelector() {
        Context context = getEHContext();
        if (context != null) {
            mTagSelectorLauncher.launch(new Intent(context, TagSelectorActivity.class));
        }
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
        if (mSearchBar != null && mSearchBar.onBackPressed()) {
            return;
        }
        if (mSearch != null) {
            onSearch(null, "");
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
    public void onDestroyView() {
        super.onDestroyView();
        if (mSearchBar != null) {
            mSearchBar.destroy();
            mSearchBar = null;
        }
        if (mHelper != null) {
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
        if (mFabLayout != null) {
            removeAboveSnackView(mFabLayout);
            mFabLayout = null;
        }
        mAdapter = null;
    }

    private static int savedSortOrdinal() {
        return Settings.getInt(KEY_SORT, SortMode.DOWNLOAD_DATE_DESC.ordinal());
    }

    private void showSortDialog() {
        Context context = getEHContext();
        if (context == null) {
            return;
        }
        int current = savedSortOrdinal();
        new AlertDialog.Builder(context)
                .setTitle(R.string.local_inventory_sort_title)
                .setSingleChoiceItems(R.array.local_inventory_sort, current, (dialog, which) -> {
                    if (which != current) {
                        Settings.putInt(KEY_SORT, which);
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

    @Override
    public void onIntoCustomChoice(EasyRecyclerView view) {
        if (mSearchBar != null) {
            mSearchBar.leaveSearchState();
        }
        showSelectionFabs();
        if (mFabLayout != null) {
            mFabLayout.setAutoCancel(false);
            // Posted: the visibility swap needs a layout pass before the expansion.
            SimpleHandler.getInstance().post(() -> {
                if (mFabLayout != null) {
                    mFabLayout.setExpanded(true);
                }
            });
        }
        if (mHelper != null) {
            mHelper.setRefreshLayoutEnable(false);
        }
        lockLeftDrawer(true);
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
        lockLeftDrawer(false);
    }

    /** Never the right one: the library has none, so MainActivity keeps it locked shut. */
    @Override
    public void lockLeftDrawer(boolean locked) {
        setDrawerLockMode(locked ? DrawerLayout.LOCK_MODE_LOCKED_CLOSED : DrawerLayout.LOCK_MODE_UNLOCKED,
                Gravity.LEFT);
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

    /** Delayed: swapping the set under the collapse animation makes the buttons flicker. */
    private void showNormalFabs() {
        SimpleHandler.getInstance().removeCallbacks(mShowNormalFabsRunnable);
        SimpleHandler.getInstance().postDelayed(mShowNormalFabsRunnable, 300);
    }

    private void showSelectionFabs() {
        SimpleHandler.getInstance().removeCallbacks(mShowNormalFabsRunnable);
        setFabs(true);
    }

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

    private static final int RESYNC_METADATA = 0;
    private static final int RESYNC_PAGES = 1;
    private static final int RESYNC_BOTH = 2;

    /** AlertDialog shows a message or a list, not both, so the caveat rides on the items. */
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

    private void replaceRow(@NonNull GalleryInfo fresh) {
        if (mHelper == null) {
            return;
        }
        for (int i = 0, n = mHelper.size(); i < n; i++) {
            GalleryInfo at = mHelper.getDataAt(i);
            if (at == null || at.gid != fresh.gid) {
                continue;
            }
            String before = NetworkStorage.active().galleryFolderName(at);
            String after = NetworkStorage.active().galleryFolderName(fresh);
            if (!before.equals(after)) {
                mPager.renameRef(before, after);
                if (mSearch != null) {
                    mSearch.renameRef(before, after);
                }
            }
            mHelper.getData().set(i, fresh);
            if (mAdapter != null) {
                mAdapter.notifyItemChanged(i);
            }
            break;
        }
    }

    private void confirmDelete(@NonNull List<GalleryInfo> galleries) {
        Context context = getEHContext();
        if (context == null || galleries.isEmpty()) {
            return;
        }
        boolean one = galleries.size() == 1;
        String title = one
                ? getString(R.string.local_inventory_delete_confirm_title)
                : getString(R.string.local_inventory_delete_confirm_title_many, galleries.size());
        String message = one
                ? getString(R.string.local_inventory_delete_confirm_message,
                        EhUtils.getSuitableTitle(galleries.get(0)), NetworkStorage.active().displayName())
                : getString(R.string.local_inventory_delete_confirm_many,
                        galleries.size(), NetworkStorage.active().displayName());
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(getString(R.string.local_inventory_delete, NetworkStorage.active().displayName()),
                        (d, w) -> deleteGalleries(galleries))
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

    private void dropRow(@NonNull GalleryInfo gi) {
        if (mHelper == null) {
            return;
        }
        // By gid: a refresh may have landed since the dialogs.
        for (int i = 0, n = mHelper.size(); i < n; i++) {
            GalleryInfo at = mHelper.getDataAt(i);
            if (at != null && at.gid == gi.gid) {
                mHelper.removeAt(i);
                break;
            }
        }
        String folderName = NetworkStorage.active().galleryFolderName(gi);
        mPager.forgetRef(folderName);
        if (mSearch != null) {
            mSearch.forgetRef(folderName);
        }
    }

    private void openDetail(@Nullable GalleryInfo gi) {
        if (gi == null) {
            return;
        }
        // The view comes back with the bar closed, so it must not leave the drawers locked.
        if (mSearchBar != null) {
            mSearchBar.leaveSearchState();
        }
        Bundle args = new Bundle();
        args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GALLERY_INFO);
        args.putParcelable(GalleryDetailScene.KEY_GALLERY_INFO, gi);
        args.putParcelable(GalleryDetailScene.KEY_GALLERY_DETAIL, NetworkStorage.active().metadata().buildOfflineDetail(gi));
        // SMB metadata carries no comments.
        args.putBoolean(GalleryDetailScene.KEY_HIDE_COMMENTS, true);
        startScene(new Announcer(GalleryDetailScene.class).setArgs(args));

        // Entries saved before tag enrichment have no tags.
        Context context = getEHContext();
        if (context != null && (gi.tgList == null || gi.tgList.isEmpty())) {
            NetworkStorage.active().metadata().enrichLocalMetadataIfMissing(context, gi);
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
        GalleryTargets.mark(gi.gid);
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
        // Offline only: a cover missing on the share stays empty rather than going online.
        holder.thumb.load(EhCacheKeyFactory.getThumbKey(gi.gid),
                gi.thumb != null ? gi.thumb : ("smb-cover://" + gi.gid),
                new SmbCoverDataContainer(gi.gid, gi.title), false, false);
        holder.thumb.setOnClickListener(v -> {
            // The thumb's own click bypasses the list's choice handling.
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

        /** One-shot: the next refresh shows the search's checked answer instead of searching again. */
        private boolean mShowCheckedAnswer;

        @Override
        protected void getPageData(int taskId, int type, int page) {
            final SortMode mode = SortMode.fromOrdinal(savedSortOrdinal());
            InventorySearch search = mSearch;
            if (search != null) {
                getSearchPage(search, mode, taskId, type, page);
                return;
            }
            final boolean rebuild = type == TYPE_REFRESH;
            mWorker.execute(() -> {
                try {
                    deliver(taskId, page, mPager.loadPageBounded(mode, page, rebuild));
                } catch (Throwable e) {
                    fail(taskId, e);
                }
            });
        }

        /** A refresh answers anew; any other page comes from the answer on screen. */
        private void getSearchPage(@NonNull InventorySearch search, @NonNull SortMode mode,
                                   int taskId, int type, int page) {
            Context context = getEHContext();
            if (context == null) {
                return;
            }
            final boolean answer = type == TYPE_REFRESH && !mShowCheckedAnswer;
            mShowCheckedAnswer = false;
            final InventoryPager.Ordering listing = mPager.ordering();
            // Looked up per answer: the share may have been switched since the search began.
            final InventorySearchCache cache = InventorySearchCache.forShare(context, NetworkStorage.active().address());
            mWorker.execute(() -> {
                try {
                    InventoryPager.Ordering shown = search.shown;
                    if (answer || shown == null) {
                        answer(search, cache, mode, listing, taskId);
                    } else {
                        deliver(taskId, page, InventoryPager.pageOf(shown, page));
                    }
                } catch (Throwable e) {
                    fail(taskId, e);
                }
            });
        }

        /** Worker thread. Shows the quick answer at once, then the checked one when it differs. */
        private void answer(@NonNull InventorySearch search, @NonNull InventorySearchCache cache,
                            @NonNull SortMode mode, @Nullable InventoryPager.Ordering listing,
                            int taskId) throws Exception {
            long t0 = SystemClock.elapsedRealtime();
            InventoryPager.Ordering quick = search.quickAnswer(cache, listing, mode);
            if (quick != null) {
                search.shown = quick;
                deliver(taskId, 0, InventoryPager.pageOf(quick, 0));
                Log.i("SmbPerf", "search.quick hits=" + quick.refs.size() + " "
                        + (SystemClock.elapsedRealtime() - t0) + "ms");
            }
            InventoryPager.Ordering checked;
            try {
                checked = search.checkedAnswer(cache, mPager, mode);
            } catch (Exception e) {
                if (quick == null) {
                    throw e;
                }
                Log.w(TAG, "Search results left unchecked", e);
                SimpleHandler.getInstance().post(() -> {
                    if (mSearch == search) {
                        showTip(getString(R.string.local_inventory_search_stale,
                                NetworkStorage.active().displayName()), LENGTH_LONG);
                    }
                });
                return;
            }
            Log.i("SmbPerf", "search.checked hits=" + checked.refs.size() + " "
                    + (SystemClock.elapsedRealtime() - t0) + "ms");
            if (quick == null) {
                search.shown = checked;
                deliver(taskId, 0, InventoryPager.pageOf(checked, 0));
            } else if (InventorySearch.sameAnswer(quick, checked)) {
                search.shown = checked;
            } else {
                SimpleHandler.getInstance().post(() -> showCheckedAnswer(search, checked));
            }
        }

        private void showCheckedAnswer(@NonNull InventorySearch search, @NonNull InventoryPager.Ordering checked) {
            if (mSearch != search || mHelper != this) {
                return;
            }
            search.shown = checked;
            mShowCheckedAnswer = true;
            doGetData(TYPE_REFRESH, 0, REFRESH_TYPE_HEADER);
        }

        private void deliver(int taskId, int page, @NonNull InventoryPager.Page result) {
            SimpleHandler.getInstance().post(() -> {
                if (!isCurrentTask(taskId)) {
                    return;
                }
                // SpiderDen reads marked gids from the share.
                for (GalleryInfo gi : result.data) {
                    GalleryTargets.mark(gi.gid);
                }
                onGetPageData(taskId, result.pages, page + 1, result.data);
                // After the page is on screen: a badge is never worth making the page wait.
                mBadges.refresh();
            });
        }

        private void fail(int taskId, @NonNull Throwable e) {
            final Exception ex = e instanceof Exception ? (Exception) e : new Exception(e);
            SimpleHandler.getInstance().post(() -> {
                if (isCurrentTask(taskId)) {
                    onGetException(taskId, ex);
                }
            });
        }

        @Override
        protected void getPageData(int taskId, int type, int page, String append) {
            getPageData(taskId, type, page);
        }

        @Override
        protected void getExPageData(int pageAction, int taskId, int page) {
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

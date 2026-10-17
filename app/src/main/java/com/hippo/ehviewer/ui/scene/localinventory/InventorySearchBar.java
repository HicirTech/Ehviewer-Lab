/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.localinventory;

import android.animation.Animator;
import android.content.Context;
import android.content.res.Resources;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.android.resource.AttrResources;
import com.hippo.drawable.AddDeleteDrawable;
import com.hippo.drawable.DrawerArrowDrawable;
import com.hippo.easyrecyclerview.FastScroller;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.exception.EhException;
import com.hippo.ehviewer.storage.InventoryQuery;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.ehviewer.ui.scene.BaseScene;
import com.hippo.ehviewer.widget.SearchBar;
import com.hippo.ehviewer.widget.SearchLayout;
import com.hippo.lib.yorozuya.AnimationUtils;
import com.hippo.lib.yorozuya.SimpleAnimatorListener;
import com.hippo.lib.yorozuya.ViewUtils;
import com.hippo.view.ViewTransition;
import com.hippo.widget.ContentLayout;
import com.hippo.widget.FabLayout;
import com.hippo.widget.SearchBarMover;

/** The online list's search bar, search layout and search button, searching the library instead. */
final class InventorySearchBar implements SearchBar.Helper, SearchBar.OnStateChangeListener,
        SearchLayout.Helper, SearchBarMover.Helper, View.OnClickListener {

    /** Its showTip is BaseScene's. */
    interface Host {
        /** A null query lists the whole library again. */
        void onSearch(@Nullable InventoryQuery query, @NonNull String keyword);

        void onBackPressed();

        void showTip(@StringRes int id, int length);

        void lockLeftDrawer(boolean locked);

        void openTagSelector();
    }

    private static final int STATE_NORMAL = 0;
    private static final int STATE_SIMPLE_SEARCH = 1;
    private static final int STATE_SEARCH = 2;
    private static final int STATE_SEARCH_SHOW_LIST = 3;

    private static final long ANIMATE_TIME = 300L;

    @NonNull
    private final Host mHost;
    @NonNull
    private final SearchBar mSearchBar;
    @NonNull
    private final SearchLayout mSearchLayout;
    @NonNull
    private final View mSearchFab;
    @NonNull
    private final FabLayout mFabLayout;
    @NonNull
    private final RecyclerView mRecyclerView;
    @NonNull
    private final ViewTransition mViewTransition;
    @NonNull
    private final SearchBarMover mSearchBarMover;
    @NonNull
    private final AddDeleteDrawable mRightDrawable;
    @NonNull
    private final String mLibraryTitle;
    @NonNull
    private final String mFilterTitle;

    private int mState = STATE_NORMAL;

    InventorySearchBar(@NonNull Host host, @NonNull View root, @NonNull ContentLayout contentLayout,
                       @NonNull FabLayout fabLayout) {
        Context context = root.getContext();
        Resources resources = context.getResources();
        mHost = host;
        mSearchBar = (SearchBar) ViewUtils.$$(root, R.id.search_bar);
        mSearchLayout = (SearchLayout) ViewUtils.$$(root, R.id.search_layout);
        mSearchFab = ViewUtils.$$(root, R.id.search_fab);
        mFabLayout = fabLayout;
        mRecyclerView = contentLayout.getRecyclerView();
        mViewTransition = new ViewTransition(contentLayout, mSearchLayout);
        String name = NetworkStorage.active().displayName();
        mLibraryTitle = resources.getString(R.string.local_inventory, name);
        mFilterTitle = resources.getString(R.string.search);

        int color = AttrResources.getAttrColor(context, R.attr.drawableColorPrimary);
        // The arrow leaves the library, as the toolbar's did.
        DrawerArrowDrawable leftDrawable = new DrawerArrowDrawable(context, color);
        leftDrawable.setArrow(0);
        mRightDrawable = new AddDeleteDrawable(context, color);
        mSearchBar.setLeftDrawable(leftDrawable);
        mSearchBar.setRightDrawable(mRightDrawable);
        mSearchBar.setHelper(this);
        mSearchBar.setOnStateChangeListener(this);
        mSearchBar.setEditTextHint(resources.getString(R.string.local_inventory_search_hint, name));

        int paddingTopSB = resources.getDimensionPixelOffset(R.dimen.gallery_padding_top_search_bar);
        int paddingBottomFab = resources.getDimensionPixelOffset(R.dimen.gallery_padding_bottom_fab);
        mSearchLayout.setHelper(this);
        mSearchLayout.setPadding(mSearchLayout.getPaddingLeft(), mSearchLayout.getPaddingTop() + paddingTopSB,
                mSearchLayout.getPaddingRight(), mSearchLayout.getPaddingBottom() + paddingBottomFab);
        mSearchFab.setOnClickListener(this);
        mSearchBarMover = new SearchBarMover(this, mSearchBar, mRecyclerView, mSearchLayout);
        contentLayout.getFastScroller().setOnDragHandlerListener(new FastScroller.OnDragHandlerListener() {
            @Override
            public void onStartDragHandler() {
            }

            @Override
            public void onEndDragHandler() {
                // A drag never settles the list, so the bar would stay where the drag left it.
                mSearchBarMover.returnSearchBarPosition();
            }
        });
    }

    /** The library's name, or what the list was searched for. */
    void showSearch(@Nullable InventorySearch search) {
        if (search == null) {
            mSearchBar.setTitle(mLibraryTitle);
            mSearchBar.setText("");
            return;
        }
        mSearchBar.setTitle(search.keyword.isEmpty() ? mFilterTitle : search.keyword);
        mSearchBar.setText(search.keyword);
        mSearchBar.cursorToEnd();
    }

    void applyTags(@NonNull String tags) {
        mSearchBar.setText(tags);
        onApplySearch(tags);
    }

    /** A selection starts over the list, not over the bar's suggestions. */
    void leaveSearchState() {
        setState(STATE_NORMAL, true);
    }

    /** True when it only closed the bar's own search state. */
    boolean onBackPressed() {
        switch (mState) {
            case STATE_SIMPLE_SEARCH:
            case STATE_SEARCH:
                setState(STATE_NORMAL, true);
                return true;
            case STATE_SEARCH_SHOW_LIST:
                setState(STATE_SEARCH, true);
                return true;
            default:
                return false;
        }
    }

    void destroy() {
        mSearchBarMover.cancelAnimation();
    }

    private void setState(int state, boolean animation) {
        if (state == mState) {
            return;
        }
        int oldState = mState;
        mState = state;
        boolean layoutShown = isLayoutState(oldState);
        if (isLayoutState(state) != layoutShown) {
            mViewTransition.showView(layoutShown ? 0 : 1, animation);
            if (!layoutShown) {
                mSearchLayout.scrollSearchContainerToTop();
            }
        }
        mSearchBar.setState(barStateOf(state), animation);
        mSearchBarMover.returnSearchBarPosition();
        if ((oldState == STATE_NORMAL) != (state == STATE_NORMAL)) {
            swapFab(state != STATE_NORMAL, animation);
        }
    }

    private static boolean isLayoutState(int state) {
        return state == STATE_SEARCH || state == STATE_SEARCH_SHOW_LIST;
    }

    private static int barStateOf(int state) {
        switch (state) {
            case STATE_SEARCH:
                return SearchBar.STATE_SEARCH;
            case STATE_SIMPLE_SEARCH:
            case STATE_SEARCH_SHOW_LIST:
                return SearchBar.STATE_SEARCH_LIST;
            default:
                return SearchBar.STATE_NORMAL;
        }
    }

    /** The search button stands in for the menu button while the bar searches. */
    private void swapFab(boolean toSearch, boolean animation) {
        View primary = mFabLayout.getPrimaryFab();
        View in = toSearch ? mSearchFab : primary;
        View out = toSearch ? primary : mSearchFab;
        if (!animation) {
            mFabLayout.setExpanded(false, false);
            out.setVisibility(View.INVISIBLE);
            out.setScaleX(0f);
            out.setScaleY(0f);
            in.setVisibility(View.VISIBLE);
            in.setScaleX(1f);
            in.setScaleY(1f);
            return;
        }
        long delay = 0L;
        if (out.getVisibility() != View.INVISIBLE) {
            delay = ANIMATE_TIME;
            out.animate().scaleX(0f).scaleY(0f).setListener(new SimpleAnimatorListener() {
                @Override
                public void onAnimationEnd(Animator animator) {
                    out.setVisibility(View.INVISIBLE);
                }
            }).setDuration(ANIMATE_TIME).setStartDelay(0L)
                    .setInterpolator(AnimationUtils.SLOW_FAST_INTERPOLATOR).start();
        }
        in.setVisibility(View.VISIBLE);
        in.setRotation(-45f);
        // A null listener drops the one a cut-short hide left behind.
        in.animate().scaleX(1f).scaleY(1f).rotation(0f).setListener(null)
                .setDuration(ANIMATE_TIME).setStartDelay(delay)
                .setInterpolator(AnimationUtils.FAST_SLOW_INTERPOLATOR).start();
    }

    @Override
    public void onClick(View v) {
        if (mState != STATE_NORMAL) {
            mSearchBar.applySearch(true);
        }
    }

    @Override
    public void onClickTitle() {
        if (mState == STATE_NORMAL) {
            setState(STATE_SIMPLE_SEARCH, true);
        }
    }

    @Override
    public void onClickLeftIcon() {
        if (mState == STATE_NORMAL) {
            mHost.onBackPressed();
        } else {
            setState(STATE_NORMAL, true);
        }
    }

    @Override
    public void onClickRightIcon() {
        if (mState == STATE_NORMAL) {
            setState(STATE_SEARCH, true);
        } else {
            mSearchBar.setText("");
        }
    }

    @Override
    public void onSearchEditTextClick() {
        if (mState == STATE_SEARCH) {
            setState(STATE_SEARCH_SHOW_LIST, true);
        }
    }

    @Override
    public void onApplySearch(String query) {
        String keyword = query != null ? query.replace("\r", "").replace("\n", "") : "";
        ListUrlBuilder builder = new ListUrlBuilder();
        if (isLayoutState(mState)) {
            try {
                mSearchLayout.formatListUrlBuilder(builder, keyword);
            } catch (EhException e) {
                // Only an image search with no image throws.
                builder.setMode(ListUrlBuilder.MODE_IMAGE_SEARCH);
            }
        } else {
            builder.setKeyword(keyword);
        }
        if (!InventoryQuery.supports(builder.getMode())) {
            mHost.showTip(R.string.local_inventory_search_unsupported, BaseScene.LENGTH_LONG);
            return;
        }
        InventoryQuery inventoryQuery = InventoryQuery.from(builder);
        mHost.onSearch(inventoryQuery.isEmpty() ? null : inventoryQuery, keyword);
        setState(STATE_NORMAL, true);
    }

    @Override
    public void onSearchEditTextBackPressed() {
        mHost.onBackPressed();
    }

    @Override
    public void onStateChange(SearchBar searchBar, int newState, int oldState, boolean animation) {
        long duration = animation ? ANIMATE_TIME : 0;
        if (newState == SearchBar.STATE_NORMAL) {
            mRightDrawable.setAdd(duration);
        } else {
            mRightDrawable.setDelete(duration);
        }
        mHost.lockLeftDrawer(newState != SearchBar.STATE_NORMAL);
    }

    @Override
    public void onChangeSearchMode() {
        mSearchBarMover.showSearchBar();
    }

    @Override
    public void onSelectImage() {
        mHost.showTip(R.string.local_inventory_search_unsupported, BaseScene.LENGTH_LONG);
    }

    @Override
    public void onOpenTagSelector() {
        mHost.openTagSelector();
    }

    @Override
    public boolean isValidView(RecyclerView recyclerView) {
        return (mState == STATE_NORMAL && recyclerView == mRecyclerView)
                || (mState == STATE_SEARCH && recyclerView == mSearchLayout);
    }

    @Override
    public RecyclerView getValidRecyclerView() {
        return isLayoutState(mState) ? mSearchLayout : mRecyclerView;
    }

    @Override
    public boolean forceShowSearchBar() {
        return mState == STATE_SIMPLE_SEARCH || mState == STATE_SEARCH_SHOW_LIST;
    }
}

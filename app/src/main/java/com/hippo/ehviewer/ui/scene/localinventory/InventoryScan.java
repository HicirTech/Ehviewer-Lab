/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.localinventory;

import android.content.Context;
import android.content.DialogInterface;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.smb.SmbDirectDownloader;
import com.hippo.ehviewer.smb.SmbDownloadBoard;
import com.hippo.ehviewer.smb.SmbTaskInfo;
import com.hippo.ehviewer.storage.GalleryCheck;
import com.hippo.ehviewer.storage.GalleryRef;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.ehviewer.storage.NetworkStorageInventory;
import com.hippo.lib.yorozuya.SimpleHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

/** Finds the galleries missing files on the share and hands the picked ones to a re-sync (#203). */
final class InventoryScan {

    /** What keeps a listed gallery out of a repair. */
    enum Blocker { NONE, NO_TOKEN, SAVING_HERE, SAVING_ELSEWHERE }

    static final class Row {
        @NonNull final GalleryCheck check;
        @NonNull final Blocker blocker;

        Row(@NonNull GalleryCheck check, @NonNull Blocker blocker) {
            this.check = check;
            this.blocker = blocker;
        }
    }

    /** Main thread. */
    interface Repair {
        void repair(@NonNull List<GalleryInfo> galleries);
    }

    private final Executor executor;
    private final Repair repair;

    InventoryScan(@NonNull Executor executor, @NonNull Repair repair) {
        this.executor = executor;
        this.repair = repair;
    }

    /** Cancelling only closes the progress: the scan runs out and reports nothing. */
    void start(@NonNull Context context) {
        AlertDialog progress = new AlertDialog.Builder(context)
                .setMessage(context.getString(R.string.local_inventory_scan_running, 0, 0))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        executor.execute(() -> {
            try {
                NetworkStorageInventory inventory = NetworkStorage.active().inventory();
                List<GalleryRef> refs = inventory.listGalleryRefs();
                List<GalleryCheck> checks = inventory.checkGalleries(refs, InventoryPager.stallMillis(),
                        (done, total) -> SimpleHandler.getInstance().post(() -> {
                            if (progress.isShowing()) {
                                progress.setMessage(context.getString(
                                        R.string.local_inventory_scan_running, done, total));
                            }
                        }));
                List<Row> rows = rowsOf(checks, savingHere(checks), savingElsewhere());
                int failed = refs.size() - checks.size();
                SimpleHandler.getInstance().post(() -> {
                    if (progress.isShowing()) {
                        progress.dismiss();
                        showResults(context, rows, failed);
                    }
                });
            } catch (IOException e) {
                fail(context, progress, e.getMessage());
            } catch (TimeoutException e) {
                fail(context, progress, InventoryPager.shareTimeout().getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(context, progress, null);
            }
        });
    }

    /** The galleries with a problem, in scan order. */
    @NonNull
    static List<Row> rowsOf(@NonNull List<GalleryCheck> checks, @NonNull Set<Long> savingHere,
                            @NonNull Set<Long> savingElsewhere) {
        List<Row> rows = new ArrayList<>();
        for (GalleryCheck check : checks) {
            if (check.hasProblem()) {
                rows.add(new Row(check, blockerOf(check, savingHere, savingElsewhere)));
            }
        }
        return rows;
    }

    @NonNull
    private static Blocker blockerOf(@NonNull GalleryCheck check, @NonNull Set<Long> savingHere,
                                     @NonNull Set<Long> savingElsewhere) {
        if (check.info == null || !check.hasToken()) {
            return Blocker.NO_TOKEN;
        }
        // Another device writes into this folder right now.
        if (savingElsewhere.contains(check.info.gid)) {
            return Blocker.SAVING_ELSEWHERE;
        }
        return savingHere.contains(check.info.gid) ? Blocker.SAVING_HERE : Blocker.NONE;
    }

    /** Two lines: what is wrong, then the title. */
    @NonNull
    static String describe(@NonNull Context context, @NonNull Row row) {
        GalleryCheck check = row.check;
        StringBuilder problems = new StringBuilder(check.expectedPages == GalleryCheck.UNKNOWN_PAGES
                ? context.getString(R.string.local_inventory_scan_pages_unknown)
                : context.getString(R.string.local_inventory_scan_pages,
                        check.expectedPages, check.missingPages));
        if (check.recordMissing) {
            problems.append(" · ").append(context.getString(R.string.local_inventory_scan_no_record));
        }
        if (check.coverMissing) {
            problems.append(" · ").append(context.getString(R.string.local_inventory_scan_no_cover));
        }
        @StringRes int blocker = blockerText(row.blocker);
        if (blocker != 0) {
            problems.append(" · ").append(context.getString(blocker));
        }
        return problems + "\n" + check.title;
    }

    @StringRes
    private static int blockerText(@NonNull Blocker blocker) {
        switch (blocker) {
            case NO_TOKEN:
                return R.string.local_inventory_scan_no_token;
            case SAVING_HERE:
                return R.string.local_inventory_scan_saving_here;
            case SAVING_ELSEWHERE:
                return R.string.local_inventory_scan_saving_elsewhere;
            default:
                return 0;
        }
    }

    /** The picked galleries a re-sync can take. */
    @NonNull
    static List<GalleryInfo> repairable(@NonNull List<Row> picked) {
        List<GalleryInfo> out = new ArrayList<>();
        for (Row row : picked) {
            if (row.blocker == Blocker.NONE && row.check.info != null) {
                out.add(row.check.info);
            }
        }
        return out;
    }

    @NonNull
    private static Set<Long> savingHere(@NonNull List<GalleryCheck> checks) {
        Set<Long> gids = new HashSet<>();
        for (GalleryCheck check : checks) {
            if (check.info != null && SmbDirectDownloader.getInstance().isQueuedOrRunning(check.info.gid)) {
                gids.add(check.info.gid);
            }
        }
        return gids;
    }

    /** SMB I/O: every device's published tasks. */
    @NonNull
    private static Set<Long> savingElsewhere() {
        Set<Long> gids = new HashSet<>();
        for (SmbTaskInfo task : SmbDownloadBoard.getInstance().snapshotSharedTasks()) {
            if (!task.mine && task.ownerAlive) {
                gids.add(task.gid);
            }
        }
        return gids;
    }

    private void showResults(@NonNull Context context, @NonNull List<Row> rows, int failed) {
        if (rows.isEmpty()) {
            Toast.makeText(context, failed == 0
                    ? context.getString(R.string.local_inventory_scan_none)
                    : context.getString(R.string.local_inventory_scan_none_failed, failed),
                    Toast.LENGTH_LONG).show();
            return;
        }
        CharSequence[] items = new CharSequence[rows.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = describe(context, rows.get(i));
        }
        boolean[] picked = new boolean[rows.size()];
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(failed == 0
                        ? context.getString(R.string.local_inventory_scan_found, rows.size())
                        : context.getString(R.string.local_inventory_scan_found_failed, rows.size(), failed))
                .setMultiChoiceItems(items, picked, (d, which, isChecked) -> picked[which] = isChecked)
                .setPositiveButton(R.string.local_inventory_scan_repair,
                        (d, which) -> repairPicked(context, rows, picked))
                .setNeutralButton(R.string.select_all, null)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        // Set after show(): a listener given to the builder closes the dialog.
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL)
                .setOnClickListener(v -> toggleAll(dialog.getListView(), picked));
    }

    /** All picked unless they already are; then none. */
    private static void toggleAll(@NonNull ListView list, @NonNull boolean[] picked) {
        boolean pick = false;
        for (boolean p : picked) {
            if (!p) {
                pick = true;
                break;
            }
        }
        for (int i = 0; i < picked.length; i++) {
            picked[i] = pick;
            list.setItemChecked(i, pick);
        }
    }

    private void repairPicked(@NonNull Context context, @NonNull List<Row> rows, @NonNull boolean[] picked) {
        List<Row> chosen = new ArrayList<>();
        for (int i = 0; i < picked.length; i++) {
            if (picked[i]) {
                chosen.add(rows.get(i));
            }
        }
        List<GalleryInfo> galleries = repairable(chosen);
        int skipped = chosen.size() - galleries.size();
        if (skipped > 0) {
            Toast.makeText(context, context.getString(R.string.local_inventory_scan_skipped, skipped),
                    Toast.LENGTH_LONG).show();
        }
        if (!galleries.isEmpty()) {
            repair.repair(galleries);
        }
    }

    private static void fail(@NonNull Context context, @NonNull AlertDialog progress,
                             @Nullable String message) {
        SimpleHandler.getInstance().post(() -> {
            if (!progress.isShowing()) {
                return;
            }
            progress.dismiss();
            if (message != null) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            }
        });
    }
}

package com.hippo.ehviewer.download;

import static org.junit.Assert.assertEquals;

import android.os.Bundle;

import com.hippo.ehviewer.Settings;

import java.util.EnumSet;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Pins when Power Download's automatic rules ask for a download, and that they ask once (#159). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class PowerDownloadSessionTest {

    private static final int SIZE = 20;
    /** What a reader reports while the page count is still being fetched. */
    private static final int SIZE_UNKNOWN = -1;

    private static final Set<PowerDownloadTarget> NOTHING = EnumSet.noneOf(PowerDownloadTarget.class);
    private static final Set<PowerDownloadTarget> PHONE = EnumSet.of(PowerDownloadTarget.PHONE);
    private static final Set<PowerDownloadTarget> SHARE = EnumSet.of(PowerDownloadTarget.NETWORK_STORAGE);

    private PowerDownloadSession session;

    @Before
    public void setUp() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        session = new PowerDownloadSession();
    }

    private static void pagesRule(int count, PowerDownloadTarget target) {
        Settings.putBoolean(PowerDownloadSettings.KEY_PAGES_ENABLED, true);
        Settings.putIntToStr(PowerDownloadSettings.KEY_PAGES_COUNT, count);
        Settings.putString(PowerDownloadSettings.KEY_PAGES_TARGET, target.value);
    }

    private static void edgeRules(boolean first, boolean last, PowerDownloadTarget target) {
        Settings.putBoolean(PowerDownloadSettings.KEY_FIRST_PAGE_ENABLED, first);
        Settings.putBoolean(PowerDownloadSettings.KEY_LAST_PAGE_ENABLED, last);
        Settings.putString(PowerDownloadSettings.KEY_EDGE_PAGE_TARGET, target.value);
    }

    @Test
    public void withNoRuleOn_nothingIsEverAskedFor() {
        for (int i = 0; i < SIZE; i++) {
            assertEquals(NOTHING, session.onPageShown(i, SIZE));
        }
    }

    /** The default N is 2, and counting starts wherever the reader opens. */
    @Test
    public void pagesRule_countsDifferentPagesFromWhereverReadingStarts() {
        Settings.putBoolean(PowerDownloadSettings.KEY_PAGES_ENABLED, true);

        assertEquals(NOTHING, session.onPageShown(5, SIZE));
        assertEquals("the same page again is not a second page", NOTHING, session.onPageShown(5, SIZE));
        assertEquals(PHONE, session.onPageShown(6, SIZE));
    }

    @Test
    public void pagesRule_aGalleryShorterThanNCountsOnceEveryPageIsSeen() {
        pagesRule(5, PowerDownloadTarget.PHONE);

        assertEquals(NOTHING, session.onPageShown(0, 3));
        assertEquals(NOTHING, session.onPageShown(1, 3));
        assertEquals(PHONE, session.onPageShown(2, 3));
    }

    @Test
    public void pagesRule_withoutAPageCountWaitsForAllNPages() {
        pagesRule(3, PowerDownloadTarget.PHONE);

        assertEquals(NOTHING, session.onPageShown(0, SIZE_UNKNOWN));
        assertEquals(NOTHING, session.onPageShown(1, SIZE_UNKNOWN));
        assertEquals(PHONE, session.onPageShown(2, SIZE_UNKNOWN));
    }

    @Test
    public void firstPageRule_asksOnPageOneAndNowhereElse() {
        edgeRules(true, false, PowerDownloadTarget.NETWORK_STORAGE);

        assertEquals("resuming mid-gallery is not the first page", NOTHING, session.onPageShown(7, SIZE));
        assertEquals(SHARE, session.onPageShown(0, SIZE));
    }

    @Test
    public void lastPageRule_needsThePageCount() {
        edgeRules(false, true, PowerDownloadTarget.PHONE);

        assertEquals("with no count, no page is known to be the last",
                NOTHING, session.onPageShown(SIZE - 1, SIZE_UNKNOWN));
        assertEquals(PHONE, session.onPageShown(SIZE - 1, SIZE));
    }

    @Test
    public void eachTargetIsAskedForOncePerReading() {
        Settings.putBoolean(PowerDownloadSettings.KEY_PAGES_ENABLED, true);
        edgeRules(true, true, PowerDownloadTarget.PHONE);

        assertEquals(PHONE, session.onPageShown(0, 2));
        assertEquals("the pages rule and the last page point at the phone too",
                NOTHING, session.onPageShown(1, 2));
    }

    @Test
    public void rulesAimedAtDifferentTargetsEachGetTheirTurn() {
        edgeRules(true, false, PowerDownloadTarget.NETWORK_STORAGE);
        Settings.putBoolean(PowerDownloadSettings.KEY_PAGES_ENABLED, true);

        assertEquals(SHARE, session.onPageShown(0, SIZE));
        assertEquals(PHONE, session.onPageShown(1, SIZE));
    }

    @Test
    public void noPageOnScreen_isNotAPage() {
        pagesRule(1, PowerDownloadTarget.PHONE);

        assertEquals(NOTHING, session.onPageShown(-1, SIZE));
    }

    /** A rotation recreates the reader; the reading goes on. */
    @Test
    public void aRotationKeepsWhatWasSeenAndWhatWasAskedFor() {
        pagesRule(3, PowerDownloadTarget.PHONE);
        edgeRules(true, false, PowerDownloadTarget.NETWORK_STORAGE);
        assertEquals(SHARE, session.onPageShown(0, SIZE));
        assertEquals(NOTHING, session.onPageShown(1, SIZE));
        Bundle state = new Bundle();
        session.saveTo(state);

        PowerDownloadSession recreated = new PowerDownloadSession();
        recreated.restoreFrom(state);

        assertEquals("page one again, but the share was already asked", NOTHING, recreated.onPageShown(0, SIZE));
        assertEquals("the third different page, two of them seen before the rotation",
                PHONE, recreated.onPageShown(2, SIZE));
    }
}

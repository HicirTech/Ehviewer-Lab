package com.hippo.ehviewer.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.preference.PreferenceManager;

import com.hippo.ehviewer.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Pins what switching network storage off does to Power Download, and the upgrade path (#159). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class PowerDownloadSettingsTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Settings.initialize(context);
    }

    // --- network storage switched off -------------------------------------------------------

    @Test
    public void storageOff_turnsOffEveryRuleAimedAtIt_andOnlyThose() {
        Settings.putBoolean(PowerDownloadSettings.KEY_PAGES_ENABLED, true);
        Settings.putString(PowerDownloadSettings.KEY_PAGES_TARGET, PowerDownloadTarget.NETWORK_STORAGE.value);
        Settings.putBoolean(PowerDownloadSettings.KEY_FIRST_PAGE_ENABLED, true);
        Settings.putBoolean(PowerDownloadSettings.KEY_LAST_PAGE_ENABLED, true);
        Settings.putString(PowerDownloadSettings.KEY_EDGE_PAGE_TARGET, PowerDownloadTarget.NETWORK_STORAGE.value);
        Settings.putBoolean(PowerDownloadSettings.KEY_MENU_ENABLED, true);
        Settings.putString(PowerDownloadSettings.KEY_MENU_TARGET, PowerDownloadTarget.PHONE.value);

        PowerDownloadSettings.turnOffNetworkStorageRules();

        assertFalse(PowerDownloadSettings.isPagesEnabled());
        assertFalse(PowerDownloadSettings.isFirstPageEnabled());
        assertFalse(PowerDownloadSettings.isLastPageEnabled());
        assertTrue("aimed at the phone, so it stays on", PowerDownloadSettings.isMenuEnabled());
        assertEquals("the target is kept for when storage comes back",
                PowerDownloadTarget.NETWORK_STORAGE, PowerDownloadSettings.getPagesTarget());
    }

    @Test
    public void storageOff_aVolumeKeyAimedAtItStopsBeingUsed() {
        Settings.putBoolean(PowerDownloadSettings.KEY_VOLUME_ENABLED, true);
        Settings.putString(PowerDownloadSettings.KEY_VOLUME_UP_TARGET, PowerDownloadTarget.PHONE.value);
        Settings.putString(PowerDownloadSettings.KEY_VOLUME_DOWN_TARGET, PowerDownloadTarget.NETWORK_STORAGE.value);

        PowerDownloadSettings.turnOffNetworkStorageRules();

        assertEquals(PowerDownloadTarget.NONE, PowerDownloadSettings.getVolumeDownTarget());
        assertEquals(PowerDownloadTarget.PHONE, PowerDownloadSettings.getVolumeUpTarget());
        assertTrue("the up key still downloads", PowerDownloadSettings.isVolumeEnabled());
    }

    @Test
    public void storageOff_withNeitherKeyLeftTheVolumeRuleGoesOff() {
        Settings.putBoolean(PowerDownloadSettings.KEY_VOLUME_ENABLED, true);
        Settings.putString(PowerDownloadSettings.KEY_VOLUME_UP_TARGET, PowerDownloadTarget.NETWORK_STORAGE.value);
        Settings.putString(PowerDownloadSettings.KEY_VOLUME_DOWN_TARGET, PowerDownloadTarget.NETWORK_STORAGE.value);

        PowerDownloadSettings.turnOffNetworkStorageRules();

        assertFalse(PowerDownloadSettings.isVolumeEnabled());
        assertFalse("page turning does not come back by itself", Settings.getVolumePage());
    }

    @Test
    public void storageOff_leavesAVolumeRuleItDidNotTouch() {
        Settings.putBoolean(PowerDownloadSettings.KEY_VOLUME_ENABLED, true);
        Settings.putString(PowerDownloadSettings.KEY_VOLUME_UP_TARGET, PowerDownloadTarget.NONE.value);
        Settings.putString(PowerDownloadSettings.KEY_VOLUME_DOWN_TARGET, PowerDownloadTarget.NONE.value);

        PowerDownloadSettings.turnOffNetworkStorageRules();

        assertTrue("no key pointed at storage, so this is the user's own setting",
                PowerDownloadSettings.isVolumeEnabled());
    }

    // --- the old "auto download to network storage" switch -------------------------------------

    @Test
    public void theOldSwitchOn_becomesTheFirstPageRuleToTheShare() {
        Settings.putBoolean(Settings.KEY_NETWORK_STORAGE_ENABLED, true);
        Settings.putBoolean(Settings.KEY_SMB_AUTO_DOWNLOAD_ENABLED, true);

        Settings.initialize(context);

        assertTrue(PowerDownloadSettings.isFirstPageEnabled());
        assertEquals(PowerDownloadTarget.NETWORK_STORAGE, PowerDownloadSettings.getEdgePageTarget());
        assertFalse(PowerDownloadSettings.isLastPageEnabled());
        assertFalse(PowerDownloadSettings.isPagesEnabled());
        assertFalse("carried over once", legacyKeyStored());
    }

    @Test
    public void theOldSwitchOff_leavesEveryRuleOff() {
        Settings.putBoolean(Settings.KEY_NETWORK_STORAGE_ENABLED, true);
        Settings.putBoolean(Settings.KEY_SMB_AUTO_DOWNLOAD_ENABLED, false);

        Settings.initialize(context);

        assertFalse(PowerDownloadSettings.isFirstPageEnabled());
        assertFalse(legacyKeyStored());
    }

    /** An old install could hold the switch on with storage off; the rule must not appear on. */
    @Test
    public void theOldSwitchOnWithStorageOff_isNotCarriedOver() {
        Settings.putBoolean(Settings.KEY_NETWORK_STORAGE_ENABLED, false);
        Settings.putBoolean(Settings.KEY_SMB_AUTO_DOWNLOAD_ENABLED, true);

        Settings.initialize(context);

        assertFalse(PowerDownloadSettings.isFirstPageEnabled());
        assertFalse(legacyKeyStored());
    }

    private boolean legacyKeyStored() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .contains(Settings.KEY_SMB_AUTO_DOWNLOAD_ENABLED);
    }

    // --- stored values ------------------------------------------------------------------------

    @Test
    public void aPagesCountBelowOneReadsAsOne_andGarbageAsTheDefault() {
        Settings.putIntToStr(PowerDownloadSettings.KEY_PAGES_COUNT, 0);
        assertEquals(1, PowerDownloadSettings.getPagesCount());

        Settings.putString(PowerDownloadSettings.KEY_PAGES_COUNT, "many");
        assertEquals(PowerDownloadSettings.DEFAULT_PAGES_COUNT, PowerDownloadSettings.getPagesCount());
    }

    @Test
    public void anUnknownStoredTargetReadsAsTheDefault() {
        Settings.putString(PowerDownloadSettings.KEY_MENU_TARGET, "tape");
        Settings.putString(PowerDownloadSettings.KEY_VOLUME_DOWN_TARGET, "tape");

        assertEquals(PowerDownloadTarget.PHONE, PowerDownloadSettings.getMenuTarget());
        assertEquals(PowerDownloadTarget.NONE, PowerDownloadSettings.getVolumeDownTarget());
    }
}

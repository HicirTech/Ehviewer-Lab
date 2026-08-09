package com.hippo.ehviewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * How this installation identifies itself to the other devices on the share (#59).
 *
 * <p>The client id names this device's file under {@code state/}. If it ever changed, the tasks
 * that file holds would be orphaned and this device would look like a new one that had published
 * nothing — a failure that shows up as work quietly going missing rather than as an error, which
 * is why it is worth pinning here rather than noticing later.
 *
 * <p>It comes from {@code ANDROID_ID} when the platform has one, and from a stored value when it
 * does not. Both branches are exercised: Robolectric supplies no {@code ANDROID_ID} of its own, so
 * the tests that want one plant it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class SmbClientIdentityTest {

    private static final String PLATFORM_ID = "a1b2c3d4e5f60718";

    private static void plantAndroidId(String value) {
        android.provider.Settings.Secure.putString(
                RuntimeEnvironment.getApplication().getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID, value);
    }

    @Before
    public void setUp() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        plantAndroidId(PLATFORM_ID);
        Settings.putString(Settings.KEY_SMB_CLIENT_ID, null);
        Settings.putString(Settings.KEY_SMB_DEVICE_NAME, "");
    }

    @Test
    public void clientId_usesThePlatformIdWhenThereIsOne() {
        assertEquals(PLATFORM_ID, Settings.getSmbClientId());
    }

    @Test
    public void clientId_isCreatedOnFirstUse() {
        String id = Settings.getSmbClientId();
        assertNotNull(id);
        assertFalse(id.isEmpty());
    }

    /** The whole point: it is an identity, not a value regenerated per call. */
    @Test
    public void clientId_isStableAcrossCalls() {
        assertEquals(Settings.getSmbClientId(), Settings.getSmbClientId());
    }

    /** And it survives a restart, which is what "persisted" has to mean here. */
    @Test
    public void clientId_survivesReinitialisation() {
        String first = Settings.getSmbClientId();
        Settings.initialize(RuntimeEnvironment.getApplication());
        assertEquals(first, Settings.getSmbClientId());
    }

    /**
     * Clearing the app's data is the case the platform id exists to survive. A value we generated
     * and stored would be gone with it, and everything this device had published on the share
     * would become unreclaimable.
     */
    @Test
    public void clientId_survivesLosingTheStoredFallback() {
        String first = Settings.getSmbClientId();
        Settings.putString(Settings.KEY_SMB_CLIENT_ID, "");

        assertEquals(first, Settings.getSmbClientId());
    }

    /** Without a platform id there is nothing to lean on, so one is made up -- and then kept. */
    @Test
    public void clientId_fallsBackToAStoredValueWhenThereIsNoPlatformId() {
        plantAndroidId(null);
        Settings.putString(Settings.KEY_SMB_CLIENT_ID, "");

        String first = Settings.getSmbClientId();
        assertNotNull(first);
        assertFalse(first.isEmpty());
        assertEquals("the made-up id must be kept, not remade", first, Settings.getSmbClientId());
    }

    /**
     * Android 2.2 handed the same id to a great many devices. Trusting it would give every one of
     * them the same file on the share.
     */
    @Test
    public void clientId_refusesTheKnownDuplicatePlatformId() {
        plantAndroidId("9774d56d682e549c");
        Settings.putString(Settings.KEY_SMB_CLIENT_ID, "");

        assertFalse("9774d56d682e549c".equals(Settings.getSmbClientId()));
    }

    /** Whatever it is, it has to be usable as a file name on the share. */
    @Test
    public void clientId_isSafeAsAFileName() {
        String id = Settings.getSmbClientId();
        assertFalse(id.contains("/"));
        assertFalse(id.contains("\\"));
        assertFalse(id.contains(" "));
        assertFalse(id.isEmpty());
    }

    // --- display name ---------------------------------------------------------------------------

    /** Unset falls back to something recognisable rather than showing nothing. */
    @Test
    public void deviceName_fallsBackWhenUnset() {
        assertFalse(Settings.getSmbDeviceName().isEmpty());
    }

    @Test
    public void deviceName_usesWhatTheUserSet() {
        Settings.putString(Settings.KEY_SMB_DEVICE_NAME, "Living room tablet");
        assertEquals("Living room tablet", Settings.getSmbDeviceName());
    }

    /** Whitespace is not a name; it should fall back rather than publish blanks. */
    @Test
    public void deviceName_treatsWhitespaceAsUnset() {
        Settings.putString(Settings.KEY_SMB_DEVICE_NAME, "");
        String whenUnset = Settings.getSmbDeviceName();

        Settings.putString(Settings.KEY_SMB_DEVICE_NAME, "   ");
        assertEquals(whenUnset, Settings.getSmbDeviceName());
    }

    @Test
    public void deviceName_isTrimmed() {
        Settings.putString(Settings.KEY_SMB_DEVICE_NAME, "  Study phone  ");
        assertEquals("Study phone", Settings.getSmbDeviceName());
    }

    /** Renaming the device must not change which state file is its own. */
    @Test
    public void deviceName_isIndependentOfTheClientId() {
        String id = Settings.getSmbClientId();
        Settings.putString(Settings.KEY_SMB_DEVICE_NAME, "Renamed");
        assertEquals(id, Settings.getSmbClientId());
    }
}

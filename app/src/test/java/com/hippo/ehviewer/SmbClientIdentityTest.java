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
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class SmbClientIdentityTest {

    @Before
    public void setUp() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.putString(Settings.KEY_SMB_CLIENT_ID, null);
        Settings.putString(Settings.KEY_SMB_DEVICE_NAME, "");
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

    @Test
    public void clientId_isRegeneratedOnlyIfItWasLost() {
        String first = Settings.getSmbClientId();
        Settings.putString(Settings.KEY_SMB_CLIENT_ID, "");
        String second = Settings.getSmbClientId();

        assertFalse(second.isEmpty());
        assertFalse("a blank stored id must not come back as blank", first.equals(second));
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

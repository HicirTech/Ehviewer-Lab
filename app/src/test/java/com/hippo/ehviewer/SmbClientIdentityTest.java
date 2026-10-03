package com.hippo.ehviewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** How this installation names itself to the other devices on the share (#59). */
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
    public void clientId_isStableAcrossCallsAndRestarts() {
        String first = Settings.getSmbClientId();
        assertNotNull(first);
        assertEquals(first, Settings.getSmbClientId());

        Settings.initialize(RuntimeEnvironment.getApplication());
        assertEquals(first, Settings.getSmbClientId());
    }

    @Test
    public void clientId_survivesLosingTheStoredFallback() {
        String first = Settings.getSmbClientId();
        Settings.putString(Settings.KEY_SMB_CLIENT_ID, "");

        assertEquals(first, Settings.getSmbClientId());
    }

    @Test
    public void clientId_fallsBackToAStoredValueWhenThereIsNoPlatformId() {
        plantAndroidId(null);
        Settings.putString(Settings.KEY_SMB_CLIENT_ID, "");

        String first = Settings.getSmbClientId();
        assertNotNull(first);
        assertFalse(first.isEmpty());
        assertEquals("the made-up id must be kept, not remade", first, Settings.getSmbClientId());
    }

    @Test
    public void clientId_refusesTheKnownDuplicatePlatformId() {
        plantAndroidId("9774d56d682e549c");
        Settings.putString(Settings.KEY_SMB_CLIENT_ID, "");

        assertFalse("9774d56d682e549c".equals(Settings.getSmbClientId()));
    }

    @Test
    public void clientId_isSafeAsAFileName() {
        String id = Settings.getSmbClientId();
        assertFalse(id.isEmpty());
        assertFalse(id.contains("/"));
        assertFalse(id.contains("\\"));
        assertFalse(id.contains(" "));
    }

    @Test
    public void deviceName_isIndependentOfTheClientId() {
        String id = Settings.getSmbClientId();
        Settings.putString(Settings.KEY_SMB_DEVICE_NAME, "Renamed");

        assertEquals("Renamed", Settings.getSmbDeviceName());
        assertEquals(id, Settings.getSmbClientId());
    }

    @Test
    public void deviceName_fallsBackWhenBlank() {
        Settings.putString(Settings.KEY_SMB_DEVICE_NAME, "");
        String whenUnset = Settings.getSmbDeviceName();
        assertFalse(whenUnset.isEmpty());

        Settings.putString(Settings.KEY_SMB_DEVICE_NAME, "   ");
        assertEquals(whenUnset, Settings.getSmbDeviceName());
    }
}

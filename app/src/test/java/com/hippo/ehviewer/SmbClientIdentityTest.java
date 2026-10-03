package com.hippo.ehviewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import com.hippo.ehviewer.storage.NetworkStorageSettings;

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
        Settings.putString(NetworkStorageSettings.KEY_SMB_CLIENT_ID, null);
        Settings.putString(NetworkStorageSettings.KEY_SMB_DEVICE_NAME, "");
    }

    @Test
    public void clientId_isStableAcrossCallsAndRestarts() {
        String first = NetworkStorageSettings.getSmbClientId();
        assertNotNull(first);
        assertEquals(first, NetworkStorageSettings.getSmbClientId());

        Settings.initialize(RuntimeEnvironment.getApplication());
        assertEquals(first, NetworkStorageSettings.getSmbClientId());
    }

    @Test
    public void clientId_survivesLosingTheStoredFallback() {
        String first = NetworkStorageSettings.getSmbClientId();
        Settings.putString(NetworkStorageSettings.KEY_SMB_CLIENT_ID, "");

        assertEquals(first, NetworkStorageSettings.getSmbClientId());
    }

    @Test
    public void clientId_fallsBackToAStoredValueWhenThereIsNoPlatformId() {
        plantAndroidId(null);
        Settings.putString(NetworkStorageSettings.KEY_SMB_CLIENT_ID, "");

        String first = NetworkStorageSettings.getSmbClientId();
        assertNotNull(first);
        assertFalse(first.isEmpty());
        assertEquals("the made-up id must be kept, not remade", first, NetworkStorageSettings.getSmbClientId());
    }

    @Test
    public void clientId_refusesTheKnownDuplicatePlatformId() {
        plantAndroidId("9774d56d682e549c");
        Settings.putString(NetworkStorageSettings.KEY_SMB_CLIENT_ID, "");

        assertFalse("9774d56d682e549c".equals(NetworkStorageSettings.getSmbClientId()));
    }

    @Test
    public void clientId_isSafeAsAFileName() {
        String id = NetworkStorageSettings.getSmbClientId();
        assertFalse(id.isEmpty());
        assertFalse(id.contains("/"));
        assertFalse(id.contains("\\"));
        assertFalse(id.contains(" "));
    }

    @Test
    public void deviceName_isIndependentOfTheClientId() {
        String id = NetworkStorageSettings.getSmbClientId();
        Settings.putString(NetworkStorageSettings.KEY_SMB_DEVICE_NAME, "Renamed");

        assertEquals("Renamed", NetworkStorageSettings.getSmbDeviceName());
        assertEquals(id, NetworkStorageSettings.getSmbClientId());
    }

    @Test
    public void deviceName_fallsBackWhenBlank() {
        Settings.putString(NetworkStorageSettings.KEY_SMB_DEVICE_NAME, "");
        String whenUnset = NetworkStorageSettings.getSmbDeviceName();
        assertFalse(whenUnset.isEmpty());

        Settings.putString(NetworkStorageSettings.KEY_SMB_DEVICE_NAME, "   ");
        assertEquals(whenUnset, NetworkStorageSettings.getSmbDeviceName());
    }
}

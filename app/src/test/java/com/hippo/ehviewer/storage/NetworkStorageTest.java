/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.smb.SmbNetworkStorage;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The backend contract (#100): the locator, and the naming rule with its inverse. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class NetworkStorageTest {

    /** Until #125 adds a selector, active() is the SMB backend, and one instance throughout. */
    @Test
    public void activeIsTheSmbBackend() {
        assertSame(SmbNetworkStorage.instance(), NetworkStorage.active());
    }

    /** The gid must survive the folder-name round trip — the inventory finds galleries by it. */
    @Test
    public void folderNameRoundTripsTheGid() {
        NetworkStorage storage = NetworkStorage.active();
        GalleryInfo gi = NetworkStorage.lookupKey(2653989L, "[Artist] Title (Convention) [English]");
        assertEquals(2653989L, storage.parseGalleryGid(storage.galleryFolderName(gi)));
    }

    /** A foreign folder name is not a gallery. */
    @Test
    public void foreignFolderNameIsNotAGallery() {
        assertEquals(NetworkStorage.NOT_A_GALLERY,
                NetworkStorage.active().parseGalleryGid("System Volume Information"));
    }

    /** Old users predate the selector: no protocol key, but a configured host names SMB. */
    @Test
    public void protocolResolutionKeepsPreSelectorUsersOnSmb() {
        assertEquals("smb", NetworkStorage.resolveProtocol(null, "192.0.2.7"));
        assertEquals("smb", NetworkStorage.resolveProtocol("", "192.0.2.7"));
    }

    /** An explicit choice wins over the host-derived fallback. */
    @Test
    public void protocolResolutionHonoursTheStoredChoice() {
        assertEquals("nfs", NetworkStorage.resolveProtocol("nfs", "192.0.2.7"));
    }

    /** No key and no host is "never configured", not a protocol. */
    @Test
    public void neverConfiguredResolvesToNoProtocol() {
        assertEquals("", NetworkStorage.resolveProtocol(null, null));
        assertEquals("", NetworkStorage.resolveProtocol("", ""));
    }

    /** The %s every protocol-mentioning string renders. */
    @Test
    public void displayNameIsTheProtocolName() {
        assertEquals("SMB", NetworkStorage.active().displayName());
    }

    /** lookupKey carries exactly gid and title — what folder naming needs. */
    @Test
    public void lookupKeyCarriesGidAndTitle() {
        GalleryInfo info = NetworkStorage.lookupKey(7L, "T");
        assertEquals(7L, info.gid);
        assertEquals("T", info.title);
        assertNotNull(NetworkStorage.lookupKey(7L, null));
        assertNull(NetworkStorage.lookupKey(7L, null).title);
    }
}

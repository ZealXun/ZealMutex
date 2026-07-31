package com.zealmutex.app.update;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class UpdateManagerTest {
    @Test
    public void comparesStableVersionsByNumericParts() {
        assertTrue(VersionComparator.compare("v1.1.0", "1.0.3") > 0);
        assertTrue(VersionComparator.compare("v1.10.0", "1.9.9") > 0);
        assertTrue(VersionComparator.compare("v1.1.0", "1.1.0") == 0);
    }

    @Test
    public void ordersDevelopmentVersionsBeforeStableRelease() {
        assertTrue(VersionComparator.compare(
                "v1.1.0-beta.2", "1.1.0-beta.1") > 0);
        assertTrue(VersionComparator.compare(
                "v1.1.0-rc.1", "1.1.0-beta.9") > 0);
        assertTrue(VersionComparator.compare(
                "v1.1.0", "1.1.0-dev") > 0);
    }
}

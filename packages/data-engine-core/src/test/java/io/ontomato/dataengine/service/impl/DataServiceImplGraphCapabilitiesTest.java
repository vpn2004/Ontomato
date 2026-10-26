package io.ontomato.dataengine.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import io.ontomato.dataengine.dataAdapter.DataAdapter;
import io.ontomato.dataengine.dataAdapter.DataAdapterCapabilities;

/**
 * The v4.1 data service no longer exposes the removed queryNextByNode endpoint. Graph exploration is now an
 * adapter capability, so ONN must advertise the boundary and reject unsupported neighbor reads while retaining the
 * generic identity conversion used by detail and evidence paths.
 */
class DataServiceImplGraphCapabilitiesTest {

    @Test
    void onnReadOnlyAdaptersRejectGraphReadsBeforeTransport() {
        DataAdapter adapter = mock(DataAdapter.class, CALLS_REAL_METHODS);
        doReturn(DataAdapterCapabilities.onnReadOnly()).when(adapter).capabilities();

        assertEquals(false, adapter.capabilities().graphRead());
        assertThrows(UnsupportedOperationException.class, () -> adapter.capabilities().requireGraphRead());
        assertThrows(UnsupportedOperationException.class, () -> adapter.queryNeighbors("Asset", "a:1"));
    }

    @Test
    void genericIdentityConversionPreservesColonsAndRejectsAnotherClass() {
        DataAdapter adapter = mock(DataAdapter.class, CALLS_REAL_METHODS);

        String reference = adapter.encodeNodeReference("Asset", "a:1");
        assertEquals("a:1", adapter.decodeNodeReference("Asset", reference));
        assertThrows(IllegalArgumentException.class, () -> adapter.decodeNodeReference("Asset", "Site:a:1"));
    }
}

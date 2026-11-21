package io.ontomato.dataengine.dataAdapter;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.ontomato.dataengine.config.DataRagConfig;
import io.ontomato.dataengine.util.HttpRequestUtil;

class DataAdapterCapabilitiesTest {

	private static final String CLASS_NAME = "/ns/device";
	private static final Map<String, Object> CLASS_DEF = Map.of("className", CLASS_NAME, "classDesc", "Device",
			"attrs", List.of(
					Map.of("name", "id", "type", "varchar", "enable", true),
					Map.of("name", "device_code", "type", "varchar", "enable", true, "primaryKey", true)));
	private static final Map<String, String> RELATION_DEF = Map.of("fromclass", CLASS_NAME, "toclass", CLASS_NAME);

	@Test
	void relationalIdentityUsesTheDeclaredPrimaryKeyAndPreservesColons() {
		DataAdapter adapter = legacyAdapter(false);
		String storedId = "tenant:device:42";

		assertEquals("device_code", adapter.objectPrimaryKey(CLASS_DEF));
		assertEquals(CLASS_NAME + ":" + storedId, adapter.encodeNodeReference(CLASS_NAME, storedId));
		assertEquals(storedId, adapter.decodeNodeReference(CLASS_NAME,
				adapter.encodeNodeReference(CLASS_NAME, storedId)));
	}

	@Test
	void relationalNodeReferencesRejectAnotherClassAndMissingPrefix() {
		DataAdapter adapter = legacyAdapter(false);

		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> adapter.decodeNodeReference(CLASS_NAME, "/ns/pump:tenant:42")),
				() -> assertThrows(IllegalArgumentException.class,
						() -> adapter.decodeNodeReference(CLASS_NAME, "tenant:42")),
				() -> assertThrows(IllegalArgumentException.class,
						() -> adapter.decodeNodeReference(CLASS_NAME, null)));
	}

	@Test
	void legacyRelationalClassesWithoutAMarkedPrimaryKeyStillUseId() {
		DataAdapter adapter = legacyAdapter(false);

		assertEquals("id", adapter.objectPrimaryKey(Map.of("className", CLASS_NAME, "attrs", List.of())));
		assertEquals("id", adapter.objectPrimaryKey(null));
	}

	@Test
	void m3IdentityKeepsTheStoredReferenceAndUsesItsImplicitId() {
		DataAdapter adapter = legacyAdapter(true);
		String storedId = "/ns/device:tenant:42";

		assertEquals("id", adapter.objectPrimaryKey(CLASS_DEF));
		assertEquals("id", adapter.objectPrimaryKey(null));
		assertEquals(storedId, adapter.encodeNodeReference(CLASS_NAME, storedId));
		assertEquals(storedId, adapter.decodeNodeReference(CLASS_NAME, storedId));
	}

	@Test
	void legacyCapabilitiesPreserveWritesAndSandboxWhileOnlyM3AdvertisesGraphReads() {
		DataAdapterCapabilities sql = legacyAdapter(false).capabilities();
		DataAdapterCapabilities m3 = legacyAdapter(true).capabilities();

		assertEquals(DataAdapterCapabilities.legacy(), sql);
		for (DataAdapterCapabilities capabilities : List.of(sql, m3)) {
			assertTrue(capabilities.query());
			assertFalse(capabilities.schemaRead());
			assertDoesNotThrow(capabilities::requireSchemaWrite);
			assertDoesNotThrow(capabilities::requireObjectWrite);
			assertDoesNotThrow(capabilities::requireEdgeWrite);
			assertDoesNotThrow(capabilities::requireSandbox);
		}
		assertFalse(sql.graphRead());
		assertTrue(m3.graphRead());
	}

	@Test
	void initialOnnCapabilitiesAllowExplicitReadsAndRejectWritesSandboxAndGraph() {
		DataAdapterCapabilities capabilities = DataAdapterCapabilities.onnReadOnly();

		assertTrue(capabilities.query());
		assertTrue(capabilities.schemaRead());
		assertAll(
				() -> assertDoesNotThrow(capabilities::requireQuery),
				() -> assertDoesNotThrow(capabilities::requireSchemaRead),
				() -> assertThrows(UnsupportedOperationException.class, capabilities::requireSchemaWrite),
				() -> assertThrows(UnsupportedOperationException.class, capabilities::requireObjectWrite),
				() -> assertThrows(UnsupportedOperationException.class, capabilities::requireEdgeWrite),
				() -> assertThrows(UnsupportedOperationException.class, capabilities::requireSandbox),
				() -> assertThrows(UnsupportedOperationException.class, capabilities::requireGraphRead));
	}

	@Test
	void defaultOntologyAndNeighborReadsRequireAnAdapterImplementation() {
		DataAdapter adapter = legacyAdapter(false);
		doReturn(DataAdapterCapabilities.onnReadOnly()).when(adapter).capabilities();

		assertAll(
				() -> assertThrows(UnsupportedOperationException.class, adapter::readOntology),
				() -> assertThrows(UnsupportedOperationException.class,
						() -> adapter.queryNeighbors(CLASS_NAME, "tenant:42")));
	}

	@Test
	void readOnlySchemaHooksRejectBeforeEnteringAnyLegacyM3Transport() {
		DataAdapter adapter = legacyAdapter(true);
		doReturn(DataAdapterCapabilities.onnReadOnly()).when(adapter).capabilities();
		DataRagConfig config = new DataRagConfig();

		try (MockedStatic<HttpRequestUtil> http = mockStatic(HttpRequestUtil.class)) {
			assertAll(
					() -> assertThrows(UnsupportedOperationException.class,
							() -> adapter.createSchemaClass(CLASS_DEF, config)),
					() -> assertThrows(UnsupportedOperationException.class,
							() -> adapter.deleteSchemaClass(CLASS_NAME, config)),
					() -> assertThrows(UnsupportedOperationException.class,
							() -> adapter.createSchemaRelationship("linked", RELATION_DEF, config)),
					() -> assertThrows(UnsupportedOperationException.class,
							() -> adapter.deleteSchemaRelationship("linked", RELATION_DEF, config)),
					() -> assertThrows(UnsupportedOperationException.class,
							() -> adapter.addSchemaAttribute(CLASS_NAME, Map.of("name", "note", "type", "text"), config)),
					() -> assertThrows(UnsupportedOperationException.class,
							() -> adapter.deleteSchemaAttribute(CLASS_NAME, "note", config)));
			http.verifyNoInteractions();
			verify(adapter, never()).useM3();
		}
	}

	@Test
	void legacySqlSchemaHooksKeepPhysicalSchemaUntouched() {
		DataAdapter adapter = legacyAdapter(false);
		DataRagConfig config = new DataRagConfig();

		try (MockedStatic<HttpRequestUtil> http = mockStatic(HttpRequestUtil.class)) {
			assertDoesNotThrow(() -> {
				adapter.createSchemaClass(CLASS_DEF, config);
				adapter.deleteSchemaClass(CLASS_NAME, config);
				adapter.createSchemaRelationship("linked", RELATION_DEF, config);
				adapter.deleteSchemaRelationship("linked", RELATION_DEF, config);
				adapter.addSchemaAttribute(CLASS_NAME, Map.of("name", "note", "type", "text"), config);
				adapter.deleteSchemaAttribute(CLASS_NAME, "note", config);
			});
			http.verifyNoInteractions();
		}
	}

	private static DataAdapter legacyAdapter(boolean m3) {
		DataAdapter adapter = mock(DataAdapter.class, CALLS_REAL_METHODS);
		doReturn(m3).when(adapter).useM3();
		return adapter;
	}
}

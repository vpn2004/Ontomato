package io.ontomato.dataengine.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.ontomato.dataengine.config.BusinessConfig;
import io.ontomato.dataengine.config.DataRagConfig;
import io.ontomato.dataengine.core.bean.User;
import io.ontomato.dataengine.dao.AuditLogDao;
import io.ontomato.dataengine.dao.JSONRuleDao;
import io.ontomato.dataengine.dataAdapter.DataAdapter;
import io.ontomato.dataengine.dataAdapter.DataAdapterCapabilities;
import io.ontomato.dataengine.dataAdapter.DataAdapterRegistry;
import io.ontomato.dataengine.service.BusinessConfigService;
import io.ontomato.dataengine.service.LangService;

/**
 * Every saved class keeps exactly one enabled varchar primary key: a class is created together with it, and an
 * operation that would break it is refused before the rule is saved. Editing the ontology only edits the model:
 * the domain's adapter may refuse a key, but no class or field is created in the data source.
 */
class AdminServiceImplPrimaryKeyTest {

	private static final String RULE = """
			{"datasetdesc":"","classlist":["/ns/device"],"relationship_rule":{},"classDef":[{"className":"/ns/device","attrs":[
			  {"name":"id","type":"varchar","enable":true,"primaryKey":true,"sourceName":"@identity"},
			  {"name":"code","type":"varchar","enable":true,"primaryKey":false,"sourceName":"code"},
			  {"name":"size","type":"int","enable":true,"primaryKey":false,"sourceName":"size"},
			  {"name":"old","type":"varchar","enable":false,"primaryKey":false,"sourceName":"old"}]}]}""";

	private final AdminServiceImpl service = new AdminServiceImpl();
	private final JSONRuleDao jsonRuleDao = mock(JSONRuleDao.class);
	private final DataAdapter adapter = mock(DataAdapter.class, CALLS_REAL_METHODS);
	private final AuditLogDao auditLogDao = mock(AuditLogDao.class);
	private final User user = mock(User.class);
	private JSONObject rule;

	@BeforeEach
	void wire() {
		rule = JSON.parseObject(RULE);
		when(jsonRuleDao.query("d1")).thenReturn(rule);
		when(user.getDomainId()).thenReturn("d1");
		BusinessConfig businessConfig = new BusinessConfig();
		BusinessConfigService businessConfigService = mock(BusinessConfigService.class);
		when(businessConfigService.get("d1")).thenReturn(businessConfig);
		DataAdapterRegistry registry = mock(DataAdapterRegistry.class);
		when(registry.create(eq(businessConfig), any())).thenReturn(adapter);
		LangService langService = mock(LangService.class);
		when(langService.get(any(), anyString())).thenAnswer(call -> call.getArgument(1));
		ReflectionTestUtils.setField(service, "dataRagConfig", new DataRagConfig());
		ReflectionTestUtils.setField(service, "jsonRuleDao", jsonRuleDao);
		ReflectionTestUtils.setField(service, "businessConfigService", businessConfigService);
		ReflectionTestUtils.setField(service, "dataAdapterRegistry", registry);
		ReflectionTestUtils.setField(service, "langService", langService);
		ReflectionTestUtils.setField(service, "auditLogDao", mock(AuditLogDao.class));
	}

	@Test
	void aNewClassIsSavedWithItsPrimaryKey() throws Exception {
		service.addClass("/ns/pump", "pump_no", "Pump", "A pump", false, false, false, "en", user);

		Map<String, Object> pump = savedClass("/ns/pump");
		assertEquals(List.of(Map.of("name", "pump_no", "showName", "pump_no", "attrDesc", "", "type", "varchar",
				"bizzkey", false, "enable", true, "primaryKey", true)), pump.get("attrs"));
		// Design 2: the adapter only checks the name and the key; no class is created in the data source.
		verify(adapter).validateClassNameFormat("/ns/pump");
		verify(adapter).validatePrimaryKeyName("pump_no");
	}

	@Test
	void addingOrDeletingFieldsClassesAndRelationshipsLeavesTheDataSourceAlone() throws Exception {
		service.addClassAttr("/ns/device", "note", "Note", "", "text", false, true, "en", user);
		service.delClassAttr("/ns/device", "code", "en", user);
		service.addRelationship("owns", "/ns/device", "/ns/device", "", "id", "id", "en", user);
		service.delClass("/ns/device", user);

		// Design 2: these edits only save the model.
		assertFalse(((List<Map<String, Object>>) savedRule().get("classDef")).stream()
				.anyMatch(def -> "/ns/device".equals(def.get("className"))));
	}

	@Test
	void aClassWithoutAUsablePrimaryKeyIsRefusedBeforeAnySideEffect() {
		assertRefused("Admin.class.primaryKeyRequired",
				() -> service.addClass("/ns/pump", " ", "Pump", "A pump", false, false, false, "en", user));
		for (String invalid : new String[] {"pump no", "pump\tno", "pump\u0001", "1pump", "pump-no"}) {
			assertRefused("Admin.attr.formatError",
					() -> service.addClass("/ns/pump", invalid, "Pump", "A pump", false, false, false, "en", user));
		}
	}

	@Test
	void aKeyTheAdapterRefusesIsNotSaved() throws Exception {
		// Design 3: which attribute may key a class is the adapter's rule (M3 keys objects by id).
		doThrow(new Exception("refused by adapter")).when(adapter).validatePrimaryKeyName("pump_no");
		assertRefused("refused by adapter",
				() -> service.addClass("/ns/pump", "pump_no", "Pump", "A pump", false, false, false, "en", user));
		doThrow(new Exception("refused by adapter")).when(adapter).validatePrimaryKeyName("code");
		assertRefused("refused by adapter", () -> service.setClassAttrPrimaryKey("/ns/device", "code", "en", user));
	}

	@Test
	void theCurrentPrimaryKeyCannotBeDeletedOrDisabled() {
		assertRefused("Admin.attr.primaryKeyLocked", () -> service.delClassAttr("/ns/device", "id", "en", user));
		assertRefused("Admin.attr.primaryKeyLocked",
				() -> service.setClassAttrDesc("/ns/device", "id", "key", "false", "en", user));
	}

	@Test
	void anyVarcharFieldMayBeDeletedOrDisabled() throws Exception {
		service.setClassAttrDesc("/ns/device", "code", "code", "false", "en", user);
		service.delClassAttr("/ns/device", "code", "en", user);

		assertEquals(List.of("id", "size", "old"), attrNames(savedClass("/ns/device")));
	}

	@Test
	void switchingThePrimaryKeyMovesTheSingleMark() throws Exception {
		service.setClassAttrPrimaryKey("/ns/device", "code", "en", user);

		Map<String, Object> device = savedClass("/ns/device");
		assertEquals(List.of("code"), ((List<Map<String, Object>>) device.get("attrs")).stream()
				.filter(attr -> Boolean.TRUE.equals(attr.get("primaryKey"))).map(attr -> attr.get("name")).toList());
	}

	@Test
	void onlyAnEnabledVarcharFieldCanBecomeThePrimaryKey() {
		for (String attr : new String[] {"size", "old", "missing"}) {
			assertRefused("Admin.attr.primaryKeyInvalid", () -> service.setClassAttrPrimaryKey("/ns/device", attr, "en", user));
		}
	}

	@Test
	void aFieldIsAddedToAClassThatHasItsPrimaryKey() throws Exception {
		service.addClassAttr("/ns/device", "note", "Note", "", "text", false, true, "en", user);

		assertEquals(List.of("id", "code", "size", "old", "note"), attrNames(savedClass("/ns/device")));
	}

	@Test
	void aSavedRuleThatBreaksTheInvariantStopsTheNextChangeBeforeItIsSaved() {
		((List<Map<String, Object>>) rule.get("classDef")).add(new java.util.HashMap<>(Map.of(
				"className", "/ns/legacy", "attrs", new ArrayList<>(List.of(Map.of("name", "n", "type", "int", "primaryKey", true))))));

		assertThrows(IllegalArgumentException.class,
				() -> service.addClassAttr("/ns/device", "note", "Note", "", "text", false, true, "en", user));
		verify(jsonRuleDao, never()).save(any(), any());
	}

	@Test
	void readOnlyAdaptersRejectSchemaEditsBeforeChangingTheOntomapOrWritingExternally() {
		doReturn(DataAdapterCapabilities.onnReadOnly()).when(adapter).capabilities();
		rule.getJSONObject("relationship_rule").put("linked", Map.of(
				"fromclass", "/ns/device", "toclass", "/ns/device", "fromField", "id", "toField", "code"));
		String originalRule = rule.toJSONString();

		assertThrows(UnsupportedOperationException.class,
				() -> service.addClass("/ns/pump", "pump_no", "Pump", "A pump", false, false, false, "en", user));
		assertThrows(UnsupportedOperationException.class, () -> service.delClass("/ns/device", user));
		assertThrows(UnsupportedOperationException.class,
				() -> service.addClassAttr("/ns/device", "note", "Note", "", "text", false, true, "en", user));
		assertThrows(UnsupportedOperationException.class,
				() -> service.delClassAttr("/ns/device", "code", "en", user));
		assertThrows(UnsupportedOperationException.class,
				() -> service.addRelationship("new_link", "/ns/device", "/ns/device", "", "id", "code", "en", user));
		assertThrows(UnsupportedOperationException.class, () -> service.delRelationship("linked", user));

		assertEquals(originalRule, rule.toJSONString());
		verifyNoInteractions(jsonRuleDao, auditLogDao);
	}

	@Test
	void readOnlyImportsReadCurrentSchemaAndPreserveBackendMappings() throws Exception {
		doReturn(DataAdapterCapabilities.onnReadOnly()).when(adapter).capabilities();
		JSONObject backend = JSON.parseObject(RULE);
		backend.put("nativeVersion", "current");
		backend.getJSONArray("classDef").getJSONObject(0).put("nativeClass", "device-v2");
		backend.getJSONArray("classDef").getJSONObject(0).getJSONArray("attrs").getJSONObject(1)
				.put("nativeAttribute", "current-code");
		doReturn(backend).when(adapter).readOntology();
		JSONObject pretty = service.getPrettyJSONRule("d1");
		pretty.put("datasetDesc", "Our devices");
		pretty.getJSONArray("classDefs").getJSONObject(0).put("desc", "Operational equipment");
		JSONObject code = pretty.getJSONArray("classDefs").getJSONObject(0).getJSONArray("attrs").getJSONObject(1);
		code.put("desc", "Device code");
		code.put("bizzKey", true);

		service.setPrettyJSONRule(pretty, "d1");

		ArgumentCaptor<Map<String, Object>> saved = ArgumentCaptor.forClass(Map.class);
		verify(jsonRuleDao).save(saved.capture(), eq("d1"));
		JSONObject result = JSON.parseObject(JSON.toJSONString(saved.getValue()));
		assertEquals("current", result.getString("nativeVersion"));
		assertEquals("Our devices", result.getString("datasetdesc"));
		assertEquals("device-v2", result.getJSONArray("classDef").getJSONObject(0).getString("nativeClass"));
		JSONObject savedCode = result.getJSONArray("classDef").getJSONObject(0).getJSONArray("attrs").getJSONObject(1);
		assertEquals("current-code", savedCode.getString("nativeAttribute"));
		assertEquals("code", savedCode.getString("sourceName"));
		assertEquals("@identity", result.getJSONArray("classDef").getJSONObject(0).getJSONArray("attrs").getJSONObject(0).getString("sourceName"));
		assertEquals("Device code", savedCode.getString("attrDesc"));
		assertEquals(true, savedCode.getBoolean("bizzkey"));
		verify(adapter).readOntology();
	}

	@Test
	void importedSourceMappingsSurviveMetadataEditsExportAndReimport() throws Exception {
		doReturn(DataAdapterCapabilities.onnReadOnly()).when(adapter).capabilities();
		JSONObject backend = JSON.parseObject(RULE);
		backend.getJSONArray("classDef").getJSONObject(0).getJSONArray("attrs").add(
				JSONObject.of("name", "onn_attr_id", "type", "varchar", "enable", true, "sourceName", "id"));
		rule = JSON.parseObject(backend.toJSONString());
		when(jsonRuleDao.query("d1")).thenAnswer(call -> rule);
		org.mockito.Mockito.doAnswer(call -> {
			rule = JSON.parseObject(JSON.toJSONString(call.getArgument(0)));
			return null;
		}).when(jsonRuleDao).save(any(), eq("d1"));
		doReturn(backend).when(adapter).readOntology();

		service.setPrettyJSONRule(service.getPrettyJSONRule("d1"), "d1");
		service.setClassAttrDesc("/ns/device", "onn_attr_id", "Native identifier", "true", "en", user);
		JSONObject exported = JSON.parseObject(service.getPrettyJSONRule("d1").toJSONString());
		service.setPrettyJSONRule(exported, "d1");

		JSONArray attrs = exported.getJSONArray("classDefs").getJSONObject(0).getJSONArray("attrs");
		assertEquals("@identity", attrs.getJSONObject(0).getString("sourceName"));
		assertEquals("id", attrs.getJSONObject(4).getString("sourceName"));
		assertEquals("Native identifier", rule.getJSONArray("classDef").getJSONObject(0).getJSONArray("attrs")
				.getJSONObject(4).getString("attrDesc"));
		assertEquals("id", rule.getJSONArray("classDef").getJSONObject(0).getJSONArray("attrs")
				.getJSONObject(4).getString("sourceName"));
	}

	@Test
	void sourceMappingRemovalOrTamperingIsRejectedBeforeSaving() throws Exception {
		doReturn(DataAdapterCapabilities.onnReadOnly()).when(adapter).capabilities();
		doReturn(JSON.parseObject(RULE)).when(adapter).readOntology();
		for (String mapping : new String[] {null, "id"}) {
			JSONObject pretty = service.getPrettyJSONRule("d1");
			JSONObject id = pretty.getJSONArray("classDefs").getJSONObject(0).getJSONArray("attrs").getJSONObject(0);
			if (mapping == null) id.remove("sourceName");
			else id.put("sourceName", mapping);
			assertThrows(UnsupportedOperationException.class, () -> service.setPrettyJSONRule(pretty, "d1"));
		}
		verify(jsonRuleDao, never()).save(any(), any());
	}

	@Test
	void readOnlyImportsRejectChangesAgainstTheBackendBeforeSaving() throws Exception {
		doReturn(DataAdapterCapabilities.onnReadOnly()).when(adapter).capabilities();
		doReturn(JSON.parseObject(RULE)).when(adapter).readOntology();
		JSONObject pretty = service.getPrettyJSONRule("d1");
		pretty.getJSONArray("classDefs").getJSONObject(0).getJSONArray("attrs").getJSONObject(1).put("type", "int");

		assertThrows(UnsupportedOperationException.class, () -> service.setPrettyJSONRule(pretty, "d1"));

		verify(adapter).readOntology();
		verify(jsonRuleDao, never()).save(any(), any());
		verifyNoInteractions(auditLogDao);
	}

	@Test
	void aStaleOntomapCannotOverwriteAChangedBackendSchema() throws Exception {
		doReturn(DataAdapterCapabilities.onnReadOnly()).when(adapter).capabilities();
		JSONObject backend = JSON.parseObject(RULE);
		backend.getJSONArray("classDef").getJSONObject(0).getJSONArray("attrs").getJSONObject(1).put("type", "text");
		doReturn(backend).when(adapter).readOntology();
		JSONObject stale = service.getPrettyJSONRule("d1");

		assertThrows(UnsupportedOperationException.class, () -> service.setPrettyJSONRule(stale, "d1"));

		verify(jsonRuleDao, never()).save(any(), any());
	}

	@Test
	void readOnlyDescriptionsStayEditableButEnablingFieldsDoesNot() throws Exception {
		doReturn(DataAdapterCapabilities.onnReadOnly()).when(adapter).capabilities();
		service.setClassAttrDesc("/ns/device", "code", "Updated description", "true", "en", user);
		assertEquals("Updated description", savedClass("/ns/device").get("attrs") instanceof List<?> attrs
				? ((Map<?, ?>) attrs.get(1)).get("attrDesc") : null);

		assertThrows(UnsupportedOperationException.class,
				() -> service.setClassAttrDesc("/ns/device", "old", "Should not enable", "true", "en", user));
		verify(jsonRuleDao, times(1)).save(any(), any());
	}

	@Test
	void disabledReadOnlyAttributesCannotBecomeBusinessKeys() {
		doReturn(DataAdapterCapabilities.onnReadOnly()).when(adapter).capabilities();
		String originalRule = rule.toJSONString();

		UnsupportedOperationException failure = assertThrows(UnsupportedOperationException.class,
				() -> service.setClassAttrShouldReturn("/ns/device", "old", true, user));

		assertEquals("Disabled backend attributes cannot be used as business keys", failure.getMessage());
		assertEquals(originalRule, rule.toJSONString());
		verify(jsonRuleDao, never()).save(any(), any());
		verifyNoInteractions(auditLogDao);
	}

	@Test
	void enabledReadOnlyBusinessKeysAndClearingDisabledBusinessKeysStayEditable() {
		doReturn(DataAdapterCapabilities.onnReadOnly()).when(adapter).capabilities();
		rule.getJSONArray("classDef").getJSONObject(0).getJSONArray("attrs").getJSONObject(3).put("bizzkey", true);

		service.setClassAttrShouldReturn("/ns/device", "code", true, user);
		service.setClassAttrShouldReturn("/ns/device", "old", false, user);

		List<Map<String, Object>> attrs = (List<Map<String, Object>>) savedClass("/ns/device").get("attrs");
		assertEquals(true, attrs.get(1).get("bizzkey"));
		assertEquals(false, attrs.get(3).get("bizzkey"));
		verify(jsonRuleDao, times(2)).save(any(), eq("d1"));
	}

	@Test
	void writableAdaptersKeepExistingDisabledBusinessKeySemantics() {
		service.setClassAttrShouldReturn("/ns/device", "old", true, user);

		List<Map<String, Object>> attrs = (List<Map<String, Object>>) savedClass("/ns/device").get("attrs");
		assertEquals(true, attrs.get(3).get("bizzkey"));
	}

	@Test
	void displayOnlyImportsRejectDisabledBusinessKeysBeforeSaving() throws Exception {
		doReturn(DataAdapterCapabilities.onnReadOnly()).when(adapter).capabilities();
		doReturn(JSON.parseObject(RULE)).when(adapter).readOntology();
		JSONObject pretty = service.getPrettyJSONRule("d1");
		pretty.getJSONArray("classDefs").getJSONObject(0).getJSONArray("attrs").getJSONObject(3).put("bizzKey", true);

		assertThrows(UnsupportedOperationException.class, () -> service.setPrettyJSONRule(pretty, "d1"));

		verify(jsonRuleDao, never()).save(any(), any());
		verifyNoInteractions(auditLogDao);
	}

	private void assertRefused(String messageKey, org.junit.jupiter.api.function.Executable operation) {
		Exception failure = assertThrows(Exception.class, operation);
		assertEquals(messageKey, failure.getMessage());
		verify(jsonRuleDao, never()).save(any(), any());
	}

	private Map<String, Object> savedRule() {
		ArgumentCaptor<Map<String, Object>> saved = ArgumentCaptor.forClass(Map.class);
		verify(jsonRuleDao, org.mockito.Mockito.atLeastOnce()).save(saved.capture(), eq("d1"));
		return saved.getValue();
	}

	private Map<String, Object> savedClass(String className) {
		return ((List<Map<String, Object>>) savedRule().get("classDef")).stream()
				.filter(classDef -> className.equals(classDef.get("className"))).findFirst().orElseThrow();
	}

	private static List<Object> attrNames(Map<String, Object> classDef) {
		return ((List<Map<String, Object>>) classDef.get("attrs")).stream().map(attr -> attr.get("name")).toList();
	}
}

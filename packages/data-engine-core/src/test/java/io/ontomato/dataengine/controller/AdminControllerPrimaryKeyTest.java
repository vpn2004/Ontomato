package io.ontomato.dataengine.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.ontomato.dataengine.config.BusinessConfig;
import io.ontomato.dataengine.config.DataRagConfig;
import io.ontomato.dataengine.core.bean.User;
import io.ontomato.dataengine.dao.AuditLogDao;
import io.ontomato.dataengine.dao.JSONRuleDao;
import io.ontomato.dataengine.dataAdapter.DataAdapter;
import io.ontomato.dataengine.dataAdapter.DataAdapterRegistry;
import io.ontomato.dataengine.service.BusinessConfigService;
import io.ontomato.dataengine.service.IdentityService;
import io.ontomato.dataengine.service.LangService;
import io.ontomato.dataengine.service.impl.AdminServiceImpl;
import io.ontomato.dataengine.util.SpringBeanUtil;

/**
 * The payloads the Manager actually sends reach the saved model through the real controller and service:
 * only the rule store, identity, adapter selection and message lookup are stand-ins (the adapter is not M3).
 */
class AdminControllerPrimaryKeyTest {

	private static final String RULE = """
			{"datasetdesc":"","classlist":["Device"],"relationship_rule":{},"classDef":[{"className":"Device","attrs":[
			  {"name":"device_no","showName":"device_no","attrDesc":"","type":"varchar","enable":true,"primaryKey":true},
			  {"name":"model","showName":"model","attrDesc":"","type":"varchar","enable":true,"primaryKey":false}]}]}""";

	private final AdminController controller = new AdminController();
	private final JSONRuleDao jsonRuleDao = mock(JSONRuleDao.class);

	@BeforeEach
	void wire() {
		User user = mock(User.class);
		when(user.getDomainId()).thenReturn("d1");
		IdentityService identityService = mock(IdentityService.class);
		when(identityService.getCurrentUser()).thenReturn(user);
		GenericApplicationContext context = new GenericApplicationContext();
		context.registerBean(IdentityService.class, () -> identityService);
		context.refresh();
		new SpringBeanUtil().setApplicationContext(context);

		when(jsonRuleDao.query("d1")).thenReturn(JSON.parseObject(RULE));
		BusinessConfig businessConfig = new BusinessConfig();
		BusinessConfigService businessConfigService = mock(BusinessConfigService.class);
		when(businessConfigService.get("d1")).thenReturn(businessConfig);
		DataAdapterRegistry registry = mock(DataAdapterRegistry.class);
		when(registry.create(eq(businessConfig), any())).thenReturn(mock(DataAdapter.class, CALLS_REAL_METHODS));
		LangService langService = mock(LangService.class);
		when(langService.get(any(), anyString())).thenAnswer(call -> call.getArgument(1));

		AdminServiceImpl service = new AdminServiceImpl();
		ReflectionTestUtils.setField(service, "dataRagConfig", new DataRagConfig());
		ReflectionTestUtils.setField(service, "jsonRuleDao", jsonRuleDao);
		ReflectionTestUtils.setField(service, "businessConfigService", businessConfigService);
		ReflectionTestUtils.setField(service, "dataAdapterRegistry", registry);
		ReflectionTestUtils.setField(service, "langService", langService);
		ReflectionTestUtils.setField(service, "auditLogDao", mock(AuditLogDao.class));
		ReflectionTestUtils.setField(controller, "adminService", service);
		ReflectionTestUtils.setField(controller, "langService", langService);
	}

	@Test
	void theCatalogPayloadWithoutFlagsAndWithAnEmptyDescriptionCreatesTheClass() {
		JSONObject ret = controller.addClass("en", JSON.parseObject(
				"{\"className\":\"Pump\",\"primaryKeyName\":\"pump_no\",\"showName\":\"Pump\",\"classDesc\":\"\"}"));

		assertTrue(ret.getBooleanValue("success"), ret.toString());
		Map<String, Object> pump = savedClass("Pump");
		assertEquals("", pump.get("classDesc"));
		assertEquals(List.of(false, false, false),
				List.of(pump.get("classToCard"), pump.get("instanceToCard"), pump.get("inStarChart")));
		assertEquals("pump_no", ((List<Map<String, Object>>) pump.get("attrs")).get(0).get("name"));
	}

	@Test
	void aClassWithoutPrimaryKeyOrNamesIsRefused() {
		assertRefused("Admin.class.primaryKeyRequired", controller.addClass("en", JSON.parseObject(
				"{\"className\":\"Pump\",\"showName\":\"Pump\",\"classDesc\":\"\"}")));
		assertRefused("Admin.class.nameRequired", controller.addClass("en", JSON.parseObject(
				"{\"className\":\"\",\"primaryKeyName\":\"pump_no\",\"showName\":\"Pump\",\"classDesc\":\"\"}")));
		assertRefused("Admin.class.nameRequired", controller.addClass("en", JSON.parseObject(
				"{\"className\":\"Pump\",\"primaryKeyName\":\"pump_no\",\"showName\":\"\",\"classDesc\":\"\"}")));
	}

	@Test
	void disablingThePrimaryKeyWithItsEmptyDescriptionIsRefused() {
		// The Manager's attribute switch sends the current (empty) description with enable=false.
		assertRefused("Admin.attr.primaryKeyLocked", controller.editClassAttrDesc("en", JSON.parseObject(
				"{\"className\":\"Device\",\"attr\":\"device_no\",\"desc\":\"\",\"enable\":\"false\"}")));
	}

	@Test
	void anotherFieldCanBeDisabledWithAnEmptyDescription() {
		JSONObject ret = controller.editClassAttrDesc("en", JSON.parseObject(
				"{\"className\":\"Device\",\"attr\":\"model\",\"desc\":\"\",\"enable\":\"false\"}"));

		assertTrue(ret.getBooleanValue("success"), ret.toString());
		Map<String, Object> model = ((List<Map<String, Object>>) savedClass("Device").get("attrs")).get(1);
		assertEquals(List.of("", false), List.of(model.get("attrDesc"), model.get("enable")));
	}

	@ParameterizedTest
	@CsvSource({
			"false, true,  int,     false, primaryKey=true, found 0",
			"true,  true,  varchar, true,  primaryKey=true, found 2",
			"true,  false, int,     true,  primaryKey=true, found 2",
			"true,  false, varchar, false, id,              enable=false",
			"true,  false, int,     false, id,              enable=false",
			"true,  true,  int,     false, varchar,         int"
	})
	void setSchemaRejectsInvalidKeysBeforeSaving(boolean primaryKey, boolean enable, String type,
			boolean extraKey, String reason, String detail) {
		JSONObject key = JSONObject.of("name", "id", "type", type, "primaryKey", primaryKey, "enable", enable);
		JSONObject other = JSONObject.of("name", "code", "type", "varchar", "primaryKey", extraKey, "enable", false);

		JSONObject ret = controller.setSchema(schemaWithAttrs(key, other));

		assertFalse(ret.getBooleanValue("success"));
		String message = ret.getString("message");
		assertTrue(message.contains("campus") && message.contains(reason) && message.contains(detail), message);
		verify(jsonRuleDao, never()).save(any(), any());
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(booleans = true)
	void setSchemaSavesAUniqueVarcharKeyWithExplicitOrDefaultEnable(Boolean enable) {
		JSONObject key = JSONObject.of("name", "id", "type", "varchar", "primaryKey", true);
		if (enable != null) key.put("enable", enable);

		JSONObject ret = controller.setSchema(schemaWithAttrs(key));

		assertTrue(ret.getBooleanValue("success"), ret.toString());
		Map<String, Object> savedKey = ((List<Map<String, Object>>) savedClass("campus").get("attrs")).get(0);
		assertEquals("id", savedKey.get("name"));
		assertEquals(true, savedKey.get("primaryKey"));
		assertEquals(enable, savedKey.get("enable"));
	}

	private static JSONObject schemaWithAttrs(JSONObject... attrs) {
		return JSONObject.of("datasetDesc", "Test", "classList", List.of("campus"), "relationshipDefs", JSONObject.of(),
				"classDefs", List.of(JSONObject.of("name", "campus", "attrs", List.of(attrs))));
	}

	private void assertRefused(String messageKey, JSONObject ret) {
		assertFalse(ret.getBooleanValue("success"));
		assertEquals(messageKey, ret.getString("message"));
		verify(jsonRuleDao, never()).save(any(), any());
	}

	private Map<String, Object> savedClass(String className) {
		ArgumentCaptor<Map<String, Object>> saved = ArgumentCaptor.forClass(Map.class);
		verify(jsonRuleDao).save(saved.capture(), eq("d1"));
		return ((List<Map<String, Object>>) saved.getValue().get("classDef")).stream()
				.filter(classDef -> className.equals(classDef.get("className"))).findFirst().orElseThrow();
	}
}

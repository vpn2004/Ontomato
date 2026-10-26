package io.ontomato.dataengine.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.ontomato.dataengine.bean.CheckSubQueryTask;
import io.ontomato.dataengine.bean.CheckTask;
import io.ontomato.dataengine.bean.DslCookerEvaluation;
import io.ontomato.dataengine.config.AgentConfig;
import io.ontomato.dataengine.config.AgentRole;
import io.ontomato.dataengine.config.BusinessConfig;
import io.ontomato.dataengine.config.DataRagConfig;
import io.ontomato.dataengine.config.ModelConfig;
import io.ontomato.dataengine.dao.BussinessExampleDao;
import io.ontomato.dataengine.dao.CheckDao;
import io.ontomato.dataengine.dao.KnowledgeDao;
import io.ontomato.dataengine.dataAdapter.DataAdapter;
import io.ontomato.dataengine.dataAdapter.DataAdapterCapabilities;
import io.ontomato.dataengine.dataAdapter.DataAdapterRegistry;
import io.ontomato.dataengine.service.AdminService;
import io.ontomato.dataengine.service.AgentWorkspaceService;
import io.ontomato.dataengine.service.BackendSessionEntrance;
import io.ontomato.dataengine.service.BusinessConfigService;
import io.ontomato.dataengine.service.LangService;
import io.ontomato.dataengine.service.SessionRegistryService;
import io.ontomato.dataengine.service.ai.MultiThreadAIChatService;

/** Real prompt/schema generation with offline sample, model, workspace and DAO boundaries. */
class PromptSchemaMappingTest {
    private static final String DOMAIN = "prompt-mapping-domain";
    private static final String SESSION = "40000000-0000-0000-0000-000000000001";
    private static final String CLASS = "/ns/device";
    private static final String DSL = """
            [{"problem":"List device identities","answer":{"steps":[{
              "graph":{"patterns":[{"objects":[{"class":"/ns/device","variable":"d"}]}]},
              "output":{"fields":[{"variable":"d","field":"id","as":"identity"},
                                    {"variable":"d","field":"onn_attr_id","as":"business_id"}]}
            }]}}]
            """;

    @TempDir
    Path temporaryDirectory;

    private final BusinessConfig config = new BusinessConfig();
    private final JSONObject rule = JSON.parseObject("""
            {"datasetdesc":"Offline mapped device fixture","classlist":["/ns/device"],
             "relationship_rule":{},"classDef":[{"className":"/ns/device","classDesc":"Devices",
             "attrs":[{"name":"id","type":"varchar","primaryKey":true,"enable":true,
                       "sourceName":"@identity","attrDesc":"Object identity"},
                      {"name":"onn_attr_id","type":"varchar","enable":true,
                       "sourceName":"id","attrDesc":"Native business ID"},
                      {"name":"name","type":"varchar","enable":true,"bizzkey":true,
                       "sourceName":"name","attrDesc":"Device name"}]}]}
            """);
    private final DataAdapter adapter = mock(DataAdapter.class);
    private final DataAdapterRegistry registry = mock(DataAdapterRegistry.class);
    private final BusinessConfigService configs = mock(BusinessConfigService.class);
    private final MultiThreadAIChatService model = mock(MultiThreadAIChatService.class);
    private final AgentWorkspaceService workspace = mock(AgentWorkspaceService.class);
    private final LangService language = mock(LangService.class);
    private Object previousEntrance;

    @BeforeEach
    void wireOfflineBoundaries() {
        config.setDataAdapter("onn-core");
        config.setDslCookerTries(1);
        config.setDslCookerTimeout(10_000L);
        ModelConfig modelConfig = new ModelConfig();
        modelConfig.setName("offline");
        config.setModels(List.of(modelConfig));
        AgentConfig agent = new AgentConfig();
        agent.setModel("offline");
        config.setAgents(Map.of(AgentRole.QUERY, agent));
        when(configs.get(DOMAIN)).thenReturn(config);
        when(language.get(any(), anyString())).thenAnswer(call -> call.getArgument(1));
        when(adapter.capabilities()).thenReturn(DataAdapterCapabilities.onnReadOnly());
        doAnswer(call -> {
            if (call.getArgument(1) != rule) {
                throw new IllegalArgumentException("Sample adapters require the current ontology mapping");
            }
            return adapter;
        }).when(registry).create(same(config), any());

        AtomicInteger sampleFiles = new AtomicInteger();
        when(adapter.getSampleDataFileName(eq("sampleData"), eq(CLASS))).thenAnswer(call ->
                temporaryDirectory.resolve("sample-" + sampleFiles.incrementAndGet() + ".json").toString());
        doAnswer(call -> {
            List<String> requested = call.getArgument(1);
            if (!List.of("id", "onn_attr_id", "name").equals(requested)) {
                throw new IllegalArgumentException("Sample projection lost a mapped identity field: " + requested);
            }
            JSONObject sourceProperties = JSONObject.of("id", "inventory-42", "name", "Mapped device");
            JSONObject sample = new JSONObject();
            for (Object value : rule.getJSONArray("classDef").getJSONObject(0).getJSONArray("attrs")) {
                JSONObject attribute = (JSONObject) value;
                String source = attribute.getString("sourceName");
                sample.put(attribute.getString("name"), "@identity".equals(source)
                        ? "native:item:42" : sourceProperties.get(source));
            }
            return JSONArray.of(sample).toJSONString();
        }).when(adapter).querySampleDataByClassName(eq(CLASS), any());
        when(model.chat(anyString(), any(), anyString(), anyString(), any(), eq(DOMAIN)))
                .thenReturn("Submitted offline DSL");

        previousEntrance = ReflectionTestUtils.getField(BackendSessionEntrance.class, "instance");
        BackendSessionEntrance entrance = new BackendSessionEntrance();
        ReflectionTestUtils.setField(entrance, "sessionRegistryService", mock(SessionRegistryService.class));
        entrance.publishInstance();
    }

    @AfterEach
    void restoreCancellationEntrance() {
        ReflectionTestUtils.setField(BackendSessionEntrance.class, "instance", previousEntrance);
    }

    @Test
    void firstDslCookerUsesTheMappingForItsSchemaAndSamples() {
        when(workspace.getSubmission(anyString())).thenReturn(DSL);

        DslCookerEvaluation result = chatService().generateDsl("List devices", 0, subQuery(),
                SESSION + "-0", SESSION, DOMAIN);

        assertNotNull(result.getDslStrWithoutTimeConvert());
        assertTrue(result.getIllegals().isEmpty());
        assertMappedPrompts(MultiThreadAIChatService.DSL_COOKER, 1);
    }

    @Test
    void retryDslCookerRecreatesItsAdapterWithTheSameMapping() {
        when(workspace.getSubmission(anyString())).thenReturn(DSL.replace("onn_attr_id", "missing_id"), DSL);

        DslCookerEvaluation result = chatService().generateDsl("List devices", 0, subQuery(),
                SESSION + "-0", SESSION, DOMAIN);

        assertNotNull(result.getDslStrWithoutTimeConvert());
        assertTrue(result.getIllegals().isEmpty());
        assertTrue(result.getUserMessage().contains("missing_id"));
        verify(workspace).clearSubmission(SESSION + "-0-try-0");
        assertMappedPrompts(MultiThreadAIChatService.DSL_COOKER, 2);
    }

    @Test
    void dslDescriberUsesMappedSamplesBeforeSavingItsExplanation() {
        CheckServiceImpl service = new CheckServiceImpl();
        CheckDao checks = mock(CheckDao.class);
        CheckTask task = new CheckTask();
        task.setSubQueryTasks(new CheckSubQueryTask[1]);
        when(checks.readTask(SESSION)).thenReturn(task);
        when(model.chat(eq(MultiThreadAIChatService.DSL_DESCRIBER), any(), anyString(), anyString(), any(), eq(DOMAIN)))
                .thenReturn("Read the mapped device.<summary>Device identities</summary>");
        ReflectionTestUtils.setField(service, "checkDao", checks);
        wireService(service);

        service.fromDslToLogicText(SESSION, 0, DSL, rule, "en", DOMAIN);

        assertMappedPrompts(MultiThreadAIChatService.DSL_DESCRIBER, 1);
        ArgumentCaptor<CheckSubQueryTask> saved = ArgumentCaptor.forClass(CheckSubQueryTask.class);
        verify(checks).addSubQueryTask(eq(SESSION), eq(0), saved.capture());
        assertEquals("Read the mapped device.", saved.getValue().getMeaning());
        assertEquals("Device identities", saved.getValue().getSummary());
    }

    private ChatServiceImpl chatService() {
        ChatServiceImpl service = new ChatServiceImpl();
        AdminService admin = mock(AdminService.class);
        when(admin.getJSONRule(DOMAIN)).thenReturn(rule);
        when(admin.getDslCookerExample(DOMAIN)).thenReturn("");
        ReflectionTestUtils.setField(service, "adminService", admin);
        ReflectionTestUtils.setField(service, "agentWorkspaceService", workspace);
        ReflectionTestUtils.setField(service, "knowledgeDao", mock(KnowledgeDao.class));
        ReflectionTestUtils.setField(service, "bussinessExampleDao", mock(BussinessExampleDao.class));
        DataRagConfig rag = new DataRagConfig();
        rag.setDslMightTimeFormats(new String[0]);
        ReflectionTestUtils.setField(service, "dataRagConfig", rag);
        wireService(service);
        return service;
    }

    private void wireService(Object service) {
        ReflectionTestUtils.setField(service, "dataAdapterRegistry", registry);
        ReflectionTestUtils.setField(service, "businessConfigService", configs);
        ReflectionTestUtils.setField(service, "multiThreadAIChatService", model);
        ReflectionTestUtils.setField(service, "langService", language);
    }

    private void assertMappedPrompts(String role, int count) {
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(count)).chat(eq(role), any(), anyString(), prompts.capture(), any(), eq(DOMAIN));
        verify(registry, times(count)).create(same(config), same(rule));
        verify(adapter, times(count)).querySampleDataByClassName(eq(CLASS), eq(List.of("id", "onn_attr_id", "name")));
        for (String prompt : prompts.getAllValues()) {
            assertTrue(prompt.contains("| id | varchar | true |"));
            assertTrue(prompt.contains("| onn_attr_id | varchar | false |"));
            assertTrue(prompt.contains("\"id\":\"native:item:42\""));
            assertTrue(prompt.contains("\"onn_attr_id\":\"inventory-42\""));
            assertTrue(prompt.contains("Mapped device"));
        }
    }

    private static JSONObject subQuery() {
        return JSONObject.of("classes", List.of(CLASS), "subQuestion", "List devices",
                "subgraph", JSONObject.of("path", new JSONArray(), "nodes", new JSONArray()));
    }
}

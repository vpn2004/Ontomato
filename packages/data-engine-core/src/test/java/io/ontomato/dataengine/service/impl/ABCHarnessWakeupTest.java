package io.ontomato.dataengine.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import com.alibaba.fastjson2.JSONObject;
import io.ontomato.dataengine.bean.abcHarness.*;
import io.ontomato.dataengine.config.*;
import io.ontomato.dataengine.core.bean.User;
import io.ontomato.dataengine.service.*;
import io.ontomato.dataengine.service.ai.MultiThreadAIChatService;
import io.ontomato.dataengine.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class ABCHarnessWakeupTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void deliversResultsAndFinishesPromptlyAfterTheModelCompletes(boolean fails) throws Exception {
        var tasks = new ABCHarnessTaskServiceImpl();
        ReflectionTestUtils.setField(tasks, "workspaceService", mock(AgentWorkspaceService.class));
        tasks.initService();
        var service = new ABCHarnessServiceImpl();
        var configService = mock(BusinessConfigService.class);
        var config = new BusinessConfig();
        config.setDslCookerTimeout(60000L);
        var model = new ModelConfig(); model.setName("test");
        var agent = new AgentConfig(); agent.setModel("test");
        config.setModels(List.of(model)); config.setAgents(Map.of(AgentRole.CODING, agent));
        when(configService.get(any())).thenReturn(config);
        when(configService.getRuntimeWholeConfig(any())).thenReturn(JSONObject.parseObject("{\"server\":{\"port\":8080}}"));
        var chat = mock(MultiThreadAIChatService.class);
        when(chat.chat(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            // Finish just after the old one-second completion poll has timed out.
            Thread.sleep(1500);
            var message = new ABCHarnessTaskMessage(); message.setSessionId("wake-test");
            message.setType(ABCHarnessTaskMessage.DATA_TYPE);
            message.setContent(JSONObject.parseObject("{\"question\":\"Count\",\"data\":[{\"count\":42}],\"code\":\"print(42)\",\"outKeyRefs\":[]}"));
            tasks.pushMessage(message);
            if (fails) throw new IllegalStateException("synthetic failure");
            return "finished";
        });
        var checks = mock(CheckServiceV2.class);
        ReflectionTestUtils.setField(service, "abcHarnessTaskService", tasks);
        ReflectionTestUtils.setField(service, "businessConfigService", configService);
        ReflectionTestUtils.setField(service, "adminService", mock(AdminService.class));
        ReflectionTestUtils.setField(service, "multiThreadAIChatService", chat);
        ReflectionTestUtils.setField(service, "checkServiceV2", checks);
        var user = mock(User.class); when(user.getId()).thenReturn("user"); when(user.getDomainId()).thenReturn("domain");
        var sent = new ArrayList<String>();
        try (var entrance = mockStatic(BackendSessionEntrance.class); var prompts = mockStatic(UserMessageUtil.class); var sse = mockStatic(SseEmitterUtil.class)) {
            prompts.when(() -> UserMessageUtil.getAbcProgrammerUserMessage(any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any())).thenReturn("test");
            sse.when(() -> SseEmitterUtil.sendSseEmitter(any(), any())).thenAnswer(call -> { sent.add(call.getArgument(1)); return true; });
            long start = System.nanoTime();
            service.abcHarness("wake-test", "Count", List.of(), "en", null, user);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(2, sent.size(), sent.toString());
            assertTrue(sent.get(0).contains("DATA_TYPE"));
            assertTrue(sent.get(1).contains(fails ? "synthetic failure" : "finished"));
            verify(checks).check(any(), argThat(programs -> programs.size() == 1 && programs.getFirst().getCode().equals("print(42)")), any(), any());
            assertTrue(elapsed < 4000, "Model completion must not wait for a ten-second sleep: " + elapsed);
        } finally {
            tasks.stopService();
        }
    }
}

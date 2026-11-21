package io.ontomato.dataengine.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import io.ontomato.dataengine.service.LangService;

class QualityCheckModelTest {
    private BusinessConfig config() {
        var config = new BusinessConfig();
        var model = new ModelConfig();
        model.setName("thinking"); model.setBaseUrl("http://localhost/v1");
        model.setApiKeys(List.of("test")); model.setModelName("test");
        config.setModels(new ArrayList<>(List.of(model)));
        var agents = new LinkedHashMap<String, AgentConfig>();
        for (String role : AgentRole.ALL) {
            var agent = new AgentConfig(); agent.setModel("thinking"); agents.put(role, agent);
        }
        config.setAgents(agents);
        return config;
    }

    @Test void optionalReviewBindingFallsBackOnlyWhenAbsentOrEmpty() {
        var config = config();
        var language = mock(LangService.class);
        assertSame(config.getModels().getFirst(), ModelResolver.resolve(config, "qualityCheck", language).model());
        var review = new AgentConfig(); config.getAgents().put("qualityCheck", review);
        assertSame(config.getModels().getFirst(), ModelResolver.resolve(config, "qualityCheck", language).model());
        review.setModel("missing");
        assertThrows(IllegalStateException.class, () -> ModelResolver.resolve(config, "qualityCheck", language));
        assertThrows(IllegalArgumentException.class, () -> ModelResolver.validateForSave(config));
    }

    @Test void reviewParametersNeverMutatePlanningOrCodingModels() {
        var config = config();
        var fast = new ModelConfig();
        fast.setName("review-fast"); fast.setBaseUrl("http://localhost/v1");
        fast.setApiKeys(List.of("test")); fast.setModelName("test");
        fast.setCustomRequestParameters(Map.of("thinking", Map.of("type", "disabled")));
        config.getModels().add(fast);
        var agent = new AgentConfig(); agent.setModel("review-fast"); config.getAgents().put("qualityCheck", agent);
        var language = mock(LangService.class);
        assertDoesNotThrow(() -> ModelResolver.validateForSave(config));
        assertSame(fast, ModelResolver.resolve(config, "qualityCheck", language).model());
        for (String role : List.of(AgentRole.QUERY, AgentRole.CODING)) {
            assertSame(config.getModels().getFirst(), ModelResolver.resolve(config, role, language).model());
            assertTrue(config.getModels().getFirst().getCustomRequestParameters().isEmpty());
        }
    }
}

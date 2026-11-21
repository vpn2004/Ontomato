package io.ontomato.dataengine.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.output.Response;
import io.ontomato.dataengine.bean.action.Action;
import io.ontomato.dataengine.bean.function.Function;
import io.ontomato.dataengine.bean.metricView.MetricView;
import io.ontomato.dataengine.service.ai.EmbeddingService;
import io.ontomato.dataengine.service.vector.VectorRetrievalGate;

/**
 * Every vector search refuses while the domain's vectors are not all of the current generation, and
 * every data write embeds and stores its vector inside the boundary a generation change waits for.
 */
class VectorRetrievalGateDaoTest {

    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final VectorRetrievalGate retrievalGate = mock(VectorRetrievalGate.class);

    private final JdbcTemplate knowledgeJdbc = mock(JdbcTemplate.class);
    private final KnowledgeDao knowledgeDao = new KnowledgeDao();
    private final JdbcTemplate exampleJdbc = mock(JdbcTemplate.class);
    private final BussinessExampleDao exampleDao = new BussinessExampleDao();
    private final JdbcTemplate spliterJdbc = mock(JdbcTemplate.class);
    private final BusinessExampleQuestionSpliterDao spliterDao = new BusinessExampleQuestionSpliterDao();
    private final JdbcTemplate actionJdbc = mock(JdbcTemplate.class);
    private final ActionDao actionDao = new ActionDao();
    private final JdbcTemplate metricJdbc = mock(JdbcTemplate.class);
    private final MetricViewDao metricViewDao = new MetricViewDao();
    private final JdbcTemplate resourceJdbc = mock(JdbcTemplate.class);
    private final VectorResourceDao resourceDao = new VectorResourceDao();

    @BeforeEach
    void setUp() {
        inject(knowledgeDao, knowledgeJdbc);
        inject(exampleDao, exampleJdbc);
        inject(spliterDao, spliterJdbc);
        inject(actionDao, actionJdbc);
        inject(metricViewDao, metricJdbc);
        inject(resourceDao, resourceJdbc);
        when(embeddingService.embedding(anyString(), anyString()))
                .thenReturn(new Response<>(Embedding.from(new float[] { 0.5f })));
        for (JdbcTemplate jdbc : List.of(knowledgeJdbc, exampleJdbc, spliterJdbc, actionJdbc, metricJdbc, resourceJdbc)) {
            when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        }
        // Like the gate: the readiness check comes first, then the search body.
        when(retrievalGate.read(anyString(), any())).thenAnswer(inv -> {
            retrievalGate.requireRetrievable(inv.getArgument(0));
            return inv.<Supplier<?>>getArgument(1).get();
        });
    }

    private void inject(Object dao, JdbcTemplate jdbc) {
        ReflectionTestUtils.setField(dao, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(dao, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(dao, "retrievalGate", retrievalGate);
    }

    @Test
    void allSixRetrievalsThrowWhenARebuildIsNeeded() {
        // Every search body runs inside the read boundary, so the refusal escapes its error handling.
        doThrow(new IllegalStateException("vectors need rebuilding")).when(retrievalGate).requireRetrievable("d1");

        assertThrows(IllegalStateException.class, () -> knowledgeDao.findKnowledge("q", 10, "d1"));
        assertThrows(IllegalStateException.class, () -> exampleDao.findBussinessExample("q", 10, "d1"));
        assertThrows(IllegalStateException.class, () -> spliterDao.findBussinessExample("q", 10, "d1"));
        assertThrows(IllegalStateException.class, () -> actionDao.find("q", null, 10, 0, "d1"));
        assertThrows(IllegalStateException.class, () -> metricViewDao.find("q", null, null, 10, 0, "d1"));
        assertThrows(IllegalStateException.class, () -> resourceDao.find("q", 10, "class1", "photos", "d1"));

        verifyNoInteractions(knowledgeJdbc, exampleJdbc, spliterJdbc, actionJdbc, metricJdbc, resourceJdbc);
    }

    @Test
    void everyDataWriteEmbedsAndStoresInsideTheVectorWriteBoundary() {
        boolean[] inside = { false };
        List<Boolean> observed = new ArrayList<>();
        when(retrievalGate.write(anyString(), any())).thenAnswer(inv -> {
            inside[0] = true;
            try {
                return inv.<Supplier<?>>getArgument(1).get();
            } finally {
                inside[0] = false;
            }
        });
        when(embeddingService.embedding(anyString(), anyString())).thenAnswer(inv -> {
            observed.add(inside[0]);
            return new Response<>(Embedding.from(new float[] { 0.5f }));
        });
        for (JdbcTemplate jdbc : List.of(knowledgeJdbc, exampleJdbc, spliterJdbc, actionJdbc, metricJdbc)) {
            when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
                observed.add(inside[0]);
                return 1;
            });
        }
        Function function = new Function();
        function.setDomainId("d1");
        Action action = new Action();
        action.setName("a");
        action.setFunction(function);
        MetricView metricView = new MetricView();
        metricView.setName("m");
        metricView.setFunction(function);
        BusinessExampleQuestionSpliterEntity split = new BusinessExampleQuestionSpliterEntity();
        split.setId("s1");

        knowledgeDao.insertKnowledge("k1", "d1", "t", "text", List.of(), 0, 1, null);
        exampleDao.insertBussinessExample(new BussinessExampleEntity(), "d1");
        spliterDao.insertBussinessExample(split, "d1");
        actionDao.save(action);
        metricViewDao.save(metricView);

        assertEquals(10, observed.size());
        assertTrue(observed.stream().allMatch(Boolean::booleanValue), observed.toString());
    }

}

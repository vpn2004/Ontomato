package io.ontomato.dataengine.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import io.ontomato.dataengine.bean.abcHarness.ABCHarnessCheck;
import io.ontomato.dataengine.bean.abcHarness.ABCHarnessProgram;
import io.ontomato.dataengine.bean.abcHarness.ABCHarnessProgramDescription;
import io.ontomato.dataengine.service.LangService;
import io.ontomato.dataengine.service.BusinessConfigService;
import io.ontomato.dataengine.dao.CheckDaoV2;
import io.ontomato.dataengine.util.UserMessageUtil;

class QualityAssessmentTest {
    @Test void persistsCompletedExplanationsBeforeSlowerProgramsFinish() throws Exception {
        var service = spy(new CheckServiceV2Impl());
        var dao = mock(CheckDaoV2.class);
        ReflectionTestUtils.setField(service, "checkDaoV2", dao);
        var firstSaved = new CountDownLatch(1);
        doAnswer(call -> {
            ABCHarnessCheck snapshot = call.getArgument(0);
            assertFalse(Boolean.TRUE.equals(snapshot.getFinished()));
            assertNull(snapshot.getScore());
            if (snapshot.getProgramDescriptions().get(0) == null) {
                assertEquals("fast", snapshot.getProgramDescriptions().get(1).getSummary());
                firstSaved.countDown();
            }
            return null;
        }).when(dao).save(any());
        doAnswer(call -> {
            int index = call.getArgument(1);
            if (index == 0) assertTrue(firstSaved.await(1, TimeUnit.SECONDS), "Fast explanation must be visible while another runs");
            var description = new ABCHarnessProgramDescription();
            description.setSummary(index == 0 ? "slow" : "fast");
            return description;
        }).when(service).describeProgram(any(), anyInt(), any(), any(), any(), any(), any());
        var result = service.describePrograms(new ABCHarnessCheck(), List.of(new ABCHarnessProgram(), new ABCHarnessProgram()), List.of(), "domain");
        assertEquals(List.of("slow", "fast"), result.stream().map(ABCHarnessProgramDescription::getSummary).toList());
        verify(dao, times(2)).save(any());
    }

    @Test void describesAtMostTwoProgramsConcurrentlyAndPreservesInputOrder() throws Exception {
        var service = spy(new CheckServiceV2Impl());
        ReflectionTestUtils.setField(service, "checkDaoV2", mock(CheckDaoV2.class));
        var secondDone = new CountDownLatch(1);
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        doAnswer(call -> {
            int index = call.getArgument(1);
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                if (index == 0) assertTrue(secondDone.await(2, TimeUnit.SECONDS));
                var description = new ABCHarnessProgramDescription();
                description.setSummary("program-" + index);
                return description;
            } finally {
                active.decrementAndGet();
                if (index == 1) secondDone.countDown();
            }
        }).when(service).describeProgram(any(), anyInt(), any(), any(), any(), any(), any());
        var result = service.describePrograms(new ABCHarnessCheck(), List.of(new ABCHarnessProgram(),
            new ABCHarnessProgram(), new ABCHarnessProgram(), new ABCHarnessProgram()), List.of(), "domain");
        assertEquals(List.of("program-0", "program-1", "program-2", "program-3"),
            result.stream().map(ABCHarnessProgramDescription::getSummary).toList());
        assertEquals(2, maximum.get());
    }

    @Test void aFailedExplanationCancelsOtherPendingExplanations() throws Exception {
        var service = spy(new CheckServiceV2Impl());
        ReflectionTestUtils.setField(service, "checkDaoV2", mock(CheckDaoV2.class));
        var slowStarted = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        doAnswer(call -> {
            if ((int) call.getArgument(1) == 0) {
                slowStarted.countDown();
                try { new CountDownLatch(1).await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { interrupted.countDown(); throw e; }
                throw new AssertionError("Slow explanation should have been cancelled");
            }
            assertTrue(slowStarted.await(2, TimeUnit.SECONDS));
            throw new IllegalStateException("synthetic model failure");
        }).when(service).describeProgram(any(), anyInt(), any(), any(), any(), any(), any());
        assertThrows(ExecutionException.class, () -> service.describePrograms(new ABCHarnessCheck(),
            List.of(new ABCHarnessProgram(), new ABCHarnessProgram()), List.of(), "domain"));
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
    }

    @Test void failedReviewIsSavedAsTerminalWithoutAScore() throws Exception {
        var service = new CheckServiceV2Impl();
        var configuration = mock(BusinessConfigService.class);
        when(configuration.get("domain")).thenThrow(new IllegalStateException("test failure"));
        var dao = mock(CheckDaoV2.class);
        var saved = new CountDownLatch(1);
        doAnswer(call -> { saved.countDown(); return null; }).when(dao).save(any());
        ReflectionTestUtils.setField(service, "businessConfigService", configuration);
        ReflectionTestUtils.setField(service, "checkDaoV2", dao);
        var check = new ABCHarnessCheck();
        check.setSessionId("failed-review");
        check.setFinished(false);
        service.check(check, List.of(new ABCHarnessProgram()), List.of(), "domain");
        assertTrue(saved.await(2, TimeUnit.SECONDS));
        assertTrue(check.getFinished());
        assertNull(check.getScore());
        assertNull(check.getConclusion());
    }

    @Test void preservesLowScoresAndRejectsIncompleteOrInvalidReviews() {
        var check = new ABCHarnessCheck();
        CheckServiceV2Impl.applyAssessment(check,
            "Missing the requested filter.<score>0</score><fitted_question>All customers</fitted_question>");
        assertEquals(0D, check.getScore());
        assertTrue(check.getFinished());
        for (String score : List.of("NaN", "Infinity", "-1", "101", "", "90 points")) {
            var invalid = new ABCHarnessCheck();
            assertThrows(IllegalArgumentException.class, () -> CheckServiceV2Impl.applyAssessment(invalid,
                "Reason<score>" + score + "</score><fitted_question>Scope</fitted_question>"));
            assertNotEquals(Boolean.TRUE, invalid.getFinished());
        }
        assertThrows(IllegalArgumentException.class, () -> CheckServiceV2Impl.applyAssessment(
            new ABCHarnessCheck(), "Reason<score>90</score><fitted_question>Truncated"));
    }

    @Test void incompleteExplanationCannotProceedToTheReviewer() {
        var description = CheckServiceV2Impl.parseDescription("code", "Filter is missing.<summary>All rows</summary>");
        assertEquals("Filter is missing.", description.getMeaning());
        assertEquals("All rows", description.getSummary());
        assertEquals("code", description.getCode());
        for (String text : List.of("Nothing tagged", "<summary>Summary only</summary>", "Meaning<summary>Truncated")) {
            assertThrows(IllegalArgumentException.class, () -> CheckServiceV2Impl.parseDescription("code", text));
        }
    }

    @Test void explanationIncludesSelectedFieldAndDirectedRelationshipDefinitions() {
        var language = mock(LangService.class);
        when(language.get("zh", "lang")).thenReturn("Chinese");
        var program = new ABCHarnessProgram();
        program.setCode("print('sentinel program')");
        Map<String, Object> rule = Map.of("datasetdesc", "Test dataset", "classDef", List.of(
            Map.of("className", "/Order", "classDesc", "Orders", "attrs", List.of(
                Map.of("name", "customer_code", "type", "varchar", "attrDesc", "External customer code"))),
            Map.of("className", "/Customer", "classDesc", "Customers", "attrs", List.of()),
            Map.of("className", "/Unrelated", "classDesc", "Excluded", "attrs", List.of())),
            "relationship_rule", Map.of("/placed_by", Map.of("fromclass", "/Order", "toclass", "/Customer", "desc", "Placed by")));
        String prompt = UserMessageUtil.getABCProgramDescriberUserMessage(program, "zh", language,
            List.of("/Order", "/Customer"), rule, "COUNT DISTINCT is supported in one step");
        assertTrue(prompt.contains("customer_code"));
        assertTrue(prompt.contains("External customer code"));
        assertTrue(prompt.contains("| /Order | /Customer | /placed_by |"));
        assertTrue(prompt.contains(program.getCode()));
        assertFalse(prompt.contains("/Unrelated"));
        assertFalse(prompt.contains("{{CLASS_DEF}}"));
        assertFalse(prompt.contains("{{RELATIONSHIP_DEF}}"));
        assertTrue(prompt.contains("COUNT DISTINCT is supported in one step"));
    }
}

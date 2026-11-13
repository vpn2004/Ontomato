package io.ontomato.dataengine.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import io.ontomato.dataengine.bean.abcHarness.ABCHarnessCheck;
import io.ontomato.dataengine.bean.abcHarness.ABCHarnessProgram;
import io.ontomato.dataengine.service.LangService;
import io.ontomato.dataengine.service.BusinessConfigService;
import io.ontomato.dataengine.dao.CheckDaoV2;
import io.ontomato.dataengine.util.UserMessageUtil;

class QualityAssessmentTest {
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
            List.of("/Order", "/Customer"), rule);
        assertTrue(prompt.contains("customer_code"));
        assertTrue(prompt.contains("External customer code"));
        assertTrue(prompt.contains("| /Order | /Customer | /placed_by |"));
        assertTrue(prompt.contains(program.getCode()));
        assertFalse(prompt.contains("/Unrelated"));
        assertFalse(prompt.contains("{{CLASS_DEF}}"));
        assertFalse(prompt.contains("{{RELATIONSHIP_DEF}}"));
    }
}

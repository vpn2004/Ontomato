package io.ontomato.dataengine.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import io.ontomato.dataengine.dataAdapter.DataAdapterRegistry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.ontomato.dataengine.bean.OriginalQuestion;
import io.ontomato.dataengine.bean.abcHarness.ABCHarnessCheck;
import io.ontomato.dataengine.bean.abcHarness.ABCHarnessProgram;
import io.ontomato.dataengine.bean.abcHarness.ABCHarnessProgramDescription;
import io.ontomato.dataengine.bean.function.Function;
import io.ontomato.dataengine.bean.metricView.MetricView;
import io.ontomato.dataengine.bean.metricView.OriginCheckResult;
import io.ontomato.dataengine.config.AgentRole;
import io.ontomato.dataengine.config.BusinessConfig;
import io.ontomato.dataengine.config.ModelResolver;
import io.ontomato.dataengine.dao.CheckDaoV2;
import io.ontomato.dataengine.dao.KnowledgeDao;
import io.ontomato.dataengine.service.AdminService;
import io.ontomato.dataengine.service.BackendSessionEntrance;
import io.ontomato.dataengine.service.BusinessConfigService;
import io.ontomato.dataengine.service.CheckServiceV2;
import io.ontomato.dataengine.service.FunctionService;
import io.ontomato.dataengine.service.LangService;
import io.ontomato.dataengine.service.MetricViewService;
import io.ontomato.dataengine.service.SessionCancelledException;
import io.ontomato.dataengine.service.ai.MultiThreadAIChatService;
import io.ontomato.dataengine.util.UserMessageUtil;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CheckServiceV2Impl implements CheckServiceV2 {
	
	@Autowired
	private BusinessConfigService businessConfigService;

	@Autowired
	private DataAdapterRegistry dataAdapterRegistry;
	
	@Autowired
    private AdminService adminService;
	
	@Autowired
	private FunctionService functionService;
	
	@Autowired
	private MetricViewService metricViewService;
	
	@Autowired
    private LangService langService;
	
	@Autowired
    private MultiThreadAIChatService multiThreadAIChatService;
	
	@Autowired
	private CheckDaoV2 checkDaoV2;

	@Autowired
	private KnowledgeDao knowledgeDao;
	
	@PostConstruct
	public void initService() {
		// Periodically delete quality check tasks from one hour ago
		new Thread(new Runnable() {
			@Override
			public void run() {
				while (true) {
					try {
						List<ABCHarnessCheck> tasks = checkDaoV2.queryList();
						for (ABCHarnessCheck task : tasks) {
							if (task.getCreateTimestamp() < System.currentTimeMillis() - 60 * 60 * 1000L) {
								checkDaoV2.delete(task.getSessionId());
							}
						}
					} catch (Exception e) {
						log.error(e.getMessage(), e);
					}
					
					try {
						Thread.sleep(60000L);
					} catch (Exception e) {}
				}
			}
		}).start();
	}
	
	@Override
	public void saveCheckTask(ABCHarnessCheck check) {
		checkDaoV2.save(check);
	}
	
	@Override
	public void check(ABCHarnessCheck check, List<ABCHarnessProgram> programs, List<String> classNames, String domainId) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					// Call the quality checker
					List<ABCHarnessProgramDescription> programDescriptions = describePrograms(check, programs, classNames, domainId);
					check.setProgramDescriptions(programDescriptions);
					check(check, domainId);
					
					// Save
					saveCheckTask(check);
					
					// Create a metric card for scores above 60
					if (check.getScore() >= 60) {
						if (programs != null) {
							for (int i = 0; i < programs.size(); i++) {
								ABCHarnessProgram program = programs.get(i);
								// function-metricView
								Function function = functionService.generateFromABCProgram(program.getQuestion(), program, check.getLang(), domainId, BackendSessionEntrance.derive(check.getSessionId(), "harness-program-" + i));
								if (function != null) {
									function = functionService.save(function, check.getLang());
									MetricView metricView = metricViewService.generateFromFunction(function);
									metricView.setType(MetricView.TYPE_GENERAL);
									Set<String> matchQuestions = new HashSet<String>();
									matchQuestions.add(program.getQuestion());
									metricView.setMatchQuestions(matchQuestions);
									OriginCheckResult originCheckResult = new OriginCheckResult();
									originCheckResult.setScore(check.getScore());
									originCheckResult.setConclusion(check.getConclusion());
									originCheckResult.setFittedQuestion(check.getFittedQuestion());
									metricView.setOriginCheckResult(originCheckResult);
									metricView.setOriginQuestion(program.getQuestion());
									metricViewService.save(metricView, check.getLang());
									// Convert to a dedicated metricView
									Function staticFunction = functionService.generateStaticFromGeneral(program.getQuestion(), function.getParameters(), function, check.getLang(), BackendSessionEntrance.derive(check.getSessionId(), "harness-static-" + i));
									if (staticFunction != null) {
										staticFunction = functionService.save(staticFunction, check.getLang());
										MetricView staticMetricView = metricViewService.generateFromFunction(staticFunction);
										staticMetricView.setType(MetricView.TYPE_STATIC);
										staticMetricView.setMatchQuestions(matchQuestions);
										staticMetricView.setOriginCheckResult(metricView.getOriginCheckResult());
										staticMetricView.setOriginQuestion(metricView.getOriginQuestion());
										metricViewService.save(staticMetricView, check.getLang());
									}
								}
							}
						}
					}
				} catch (Exception e) {
					if (SessionCancelledException.findCancelled(e) != null) {
						log.info("check cancelled: " + check.getSessionId());
						return;
					}
					log.error(e.getMessage(), e);
					if (!Boolean.TRUE.equals(check.getFinished())) {
						// A terminal failure has no score; readers must not wait forever or accept it as a review.
						check.setFinished(true);
						check.setScore(null);
						check.setConclusion(null);
						check.setFittedQuestion(null);
						saveCheckTask(check);
					}
				}
			}
		}).start();
		
	}
	
	List<ABCHarnessProgramDescription> describePrograms(ABCHarnessCheck check, List<ABCHarnessProgram> programs,
			List<String> classNames, String domainId) throws Exception {
		if (programs == null || programs.isEmpty()) return List.of();
		// At most two model requests per review; each has its own derived session and context.
		var executor = Executors.newFixedThreadPool(Math.min(2, programs.size()));
		var completed = new ExecutorCompletionService<IndexedDescription>(executor);
		List<Future<IndexedDescription>> tasks = new ArrayList<>();
		try {
			for (int i = 0; i < programs.size(); i++) {
				int index = i;
				tasks.add(completed.submit(() -> new IndexedDescription(index, describeProgram(check.getSessionId(), index,
					check.getOriginQuestion(), programs.get(index), classNames, check.getLang(), domainId))));
			}
			List<ABCHarnessProgramDescription> result = new ArrayList<>(Collections.nCopies(programs.size(), null));
			for (int i = 0; i < programs.size(); i++) {
				var item = completed.take().get();
				result.set(item.index(), item.description());
			}
			return result;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw e;
		} finally {
			for (var task : tasks) task.cancel(true);
			executor.shutdownNow();
		}
	}

	private record IndexedDescription(int index, ABCHarnessProgramDescription description) {}

	ABCHarnessProgramDescription describeProgram(String sessionId, int index, String originQuestion, ABCHarnessProgram program, List<String> classNames, String lang, String domainId) {
		OriginalQuestion originalQuestion = new OriginalQuestion();
		originalQuestion.setId(sessionId);
		originalQuestion.setQuestion(originQuestion);
		BusinessConfig businessConfig = businessConfigService.get(domainId);
		Map<String, Object> jsonRule = adminService.getJSONRule(domainId);
		String rules = dataAdapterRegistry.create(businessConfig, jsonRule).queryPlanningRules();
		String userMessage = UserMessageUtil.getABCProgramDescriberUserMessage(program, lang, langService, classNames, jsonRule, rules);
		String str = multiThreadAIChatService.chat(
				MultiThreadAIChatService.ABC_PROGRAM_DESCRIBER,
				originalQuestion,
				BackendSessionEntrance.derive(sessionId, "program-describer-" + index),
				userMessage,
				ModelResolver.resolve(businessConfig, AgentRole.QUERY, langService),
				domainId);
		return parseDescription(program.getCode(), str);
	}

	static ABCHarnessProgramDescription parseDescription(String code, String str) {
		if (str.startsWith("```markdown")) {
			str = str.substring(11);
			if (str.endsWith("```")) {
				str = str.substring(0, str.length() - 3);
			}
		}
		String logic = "";
		String summary = "";
		if (str.lastIndexOf("<summary>") >= 0) {
			logic = str.substring(0, str.lastIndexOf("<summary>"));
			summary = str.substring(str.lastIndexOf("<summary>") + 9);
			if (summary.lastIndexOf("</summary>") >= 0) {
				summary = summary.substring(0, summary.lastIndexOf("</summary>"));
			}
    	}
		
		ABCHarnessProgramDescription description = new ABCHarnessProgramDescription();
		if (logic.isBlank() || summary.isBlank() || !str.contains("</summary>")) {
			throw new IllegalArgumentException("Incomplete program explanation");
		}
		description.setCode(code);
		description.setMeaning(logic);
		description.setSummary(summary);
		return description;
	}
	
	private void check(ABCHarnessCheck check, String domainId) {
		BusinessConfig businessConfig = businessConfigService.get(domainId);
		OriginalQuestion originalQuestion = new OriginalQuestion();
		originalQuestion.setId(check.getSessionId());
		originalQuestion.setQuestion(check.getOriginQuestion());
		String userMessage = UserMessageUtil.getQuestionCheckerV2UserMessage(check.getSessionId(), check.getOriginQuestion(), check.getProgramDescriptions(), 
				knowledgeDao,
				businessConfigService, check.getLang(), langService, domainId);
		String str = multiThreadAIChatService.chat(
				MultiThreadAIChatService.QUESTION_CHECKER_V2,
				originalQuestion,
				BackendSessionEntrance.derive(check.getSessionId(), "question-checker"),
				userMessage,
				ModelResolver.resolve(businessConfig, AgentRole.QUERY, langService),
				domainId);
		applyAssessment(check, str);
	}

	static void applyAssessment(ABCHarnessCheck check, String str) {
		if (str.startsWith("```markdown")) {
			str = str.substring(11);
			if (str.endsWith("```")) {
				str = str.substring(0, str.length() - 3);
			}
		}
		String conclusion = "";
		String scoreStr = "";
		String fittedQuestion = "";
		if (str.lastIndexOf("<score>") >= 0) {
			conclusion = str.substring(0, str.lastIndexOf("<score>"));
			scoreStr = str.substring(str.lastIndexOf("<score>") + 7);
			if (scoreStr.lastIndexOf("</score>") >= 0) {
				scoreStr = scoreStr.substring(0, scoreStr.lastIndexOf("</score>"));
			}
    	}
		if (str.lastIndexOf("<fitted_question>") >= 0) {
			fittedQuestion = str.substring(str.lastIndexOf("<fitted_question>") + 17);
			if (fittedQuestion.lastIndexOf("</fitted_question>") >= 0) {
				fittedQuestion = fittedQuestion.substring(0, fittedQuestion.lastIndexOf("</fitted_question>"));
			}
    	}
		if (fittedQuestion.startsWith("\n")) {
			fittedQuestion = fittedQuestion.substring(1);
		}
		if (fittedQuestion.endsWith("\n")) {
			fittedQuestion = fittedQuestion.substring(0, fittedQuestion.length() - 1);
		}
		double score = Double.parseDouble(scoreStr.trim());
		if (!Double.isFinite(score) || score < 0 || score > 100 || conclusion.isBlank()
				|| fittedQuestion.isBlank() || !str.contains("</score>") || !str.contains("</fitted_question>")) {
			throw new IllegalArgumentException("Incomplete quality assessment");
		}
		
		check.setConclusion(conclusion);
		check.setScore(score);
		check.setFittedQuestion(fittedQuestion);
		check.setFinished(true);
	}

	@Override
	public ABCHarnessCheck queryBySessionId(String sessionId) {
		ABCHarnessCheck check = checkDaoV2.queryBySessionId(sessionId);
		return check;
	}

}

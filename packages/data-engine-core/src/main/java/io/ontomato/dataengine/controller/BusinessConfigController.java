package io.ontomato.dataengine.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONReader;
import io.ontomato.dataengine.config.AgentConfig;
import io.ontomato.dataengine.config.AgentRole;
import io.ontomato.dataengine.config.BusinessConfig;
import io.ontomato.dataengine.config.BusinessConfig.EmbeddingModelProperties;
import io.ontomato.dataengine.config.ControllerConst;
import io.ontomato.dataengine.config.ModelConfig;
import io.ontomato.dataengine.config.ModelResolver;
import io.ontomato.dataengine.core.bean.User;
import io.ontomato.dataengine.config.CustomRequestParameters;
import io.ontomato.dataengine.config.multisource.DataSourceEntry;
import io.ontomato.dataengine.config.multisource.MultiDataSourceDefinition;
import io.ontomato.dataengine.dataAdapter.ConfigurationProblem;
import io.ontomato.dataengine.dataAdapter.DataAdapter;
import io.ontomato.dataengine.dataAdapter.DataAdapterConnection;
import io.ontomato.dataengine.dataAdapter.DataAdapterConnectionTester;
import io.ontomato.dataengine.dataAdapter.DataAdapterProvider;
import io.ontomato.dataengine.dataAdapter.DataAdapterRegistry;
import io.ontomato.dataengine.dataAdapter.MultiDataSourceValidator;
import io.ontomato.dataengine.service.AdminService;
import io.ontomato.dataengine.service.BusinessConfigService;
import io.ontomato.dataengine.service.LangService;
import io.ontomato.dataengine.service.ai.EmbeddingService;
import io.ontomato.dataengine.service.ai.MultiThreadAIChatService;
import io.ontomato.dataengine.service.vector.VectorRebuildService;
import io.ontomato.dataengine.service.vector.VectorRebuildStatus;
import io.ontomato.dataengine.util.SystemUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
public class BusinessConfigController {

	private static final String MULTI = "multi";

	@Autowired
	private BusinessConfigService businessConfigService;
	
	@Autowired
	private MultiThreadAIChatService multiThreadAIChatService;
	
	@Autowired
	private EmbeddingService embeddingService;

	@Autowired
	private LangService langService;

	@Autowired
	private VectorRebuildService vectorRebuildService;
	
	@Autowired
	private DataAdapterRegistry dataAdapterRegistry;

	@Autowired
	private AdminService adminService;
	
	@PostMapping("/businessConfig/testModel")
    @ResponseBody
    public JSONObject testModel(@RequestBody JSONObject input) {
    	JSONObject ret = new JSONObject();
    	try {
    		User user = SystemUtils.getCurUser();
			ModelConfig model = input.getJSONObject("model").toJavaObject(
					ModelConfig.class, JSONReader.Feature.DisableReferenceDetect);
			model.setCustomRequestParameters(CustomRequestParameters.normalize(
					input.getJSONObject("model").get("customRequestParameters")));
    		String userContent = input.getString("prompt");
    		if (userContent == null || "".equals(userContent.trim())) {
    			userContent = "Hello";
    		}
    		String aiContent = multiThreadAIChatService.testModel(model, userContent, user.getDomainId());
    		ret.put("data", aiContent);
    		ret.put("success", true);
    	} catch (Exception e) {
    		log.error(e.getMessage(), e);
    		ret.put("success", false);
    		ret.put("message", e.getMessage());
    	}
		return ret;
	}

	@GetMapping("/businessConfig/getModelSettings")
	@ResponseBody
	public JSONObject getModelSettings() {
		JSONObject ret = new JSONObject();
		try {
			User user = SystemUtils.getCurUser();
			BusinessConfig businessConfig = businessConfigService.get(user.getDomainId());
			ModelResolver.normalizeForRead(businessConfig.getModels());
			JSONObject data = new JSONObject();
			data.put("models", businessConfig.getModels());
			data.put("agents", businessConfig.getAgents());
			ret.put("data", data);
			ret.put("success", true);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			ret.put("success", false);
			ret.put("message", e.getMessage());
		}
		return ret;
	}

	@PostMapping("/businessConfig/saveModelSettings")
	@ResponseBody
	public JSONObject saveModelSettings(@RequestBody JSONObject input) {
		JSONObject ret = new JSONObject();
		try {
			User user = SystemUtils.getCurUser();
			if (input.getJSONArray("models") == null) {
				throw new IllegalArgumentException("models must not be null");
			}
			List<ModelConfig> models = input.getJSONArray("models").toJavaList(
					ModelConfig.class, JSONReader.Feature.DisableReferenceDetect);
			if (models == null) {
				models = new ArrayList<>();
			}
			for (ModelConfig model : models) {
				model.setCustomRequestParameters(CustomRequestParameters.normalize(
						model.getCustomRequestParameters()));
			}

			Object rawAgents = input.get("agents");
			if (!(rawAgents instanceof Map<?, ?> agentsMap)) {
				throw new IllegalArgumentException("agents must be an object");
			}
			if (!agentsMap.keySet().equals(AgentRole.ALL)) {
				throw new IllegalArgumentException(
						"agents must contain all five roles and no unknown roles: " + agentsMap.keySet());
			}

			BusinessConfig stored = businessConfigService.get(user.getDomainId());
			String existingSkillDir = stored.getAgents().get(AgentRole.CODING).getSkilldir();
			// The first model saved into an empty library also fills every empty role reference, so a first
			// setup needs no separate role step; a batch from empty, a second model or later edits assign nothing.
			String firstModelName = stored.getModels().isEmpty() && models.size() == 1 ? models.get(0).getName() : null;

			BusinessConfig candidate = new BusinessConfig();
			BeanUtils.copyProperties(stored, candidate);
			candidate.setModels(models);

			Map<String, AgentConfig> newAgents = new LinkedHashMap<>();
			for (String role : AgentRole.ALL) {
				Object rawModelRef = agentsMap.get(role);
				String modelRef = null;
				if (rawModelRef != null) {
					if (!(rawModelRef instanceof String)) {
						throw new IllegalArgumentException("The model reference of role " + role + " must be a string or null");
					}
					String str = ((String) rawModelRef).trim();
					if (!str.isEmpty()) {
						modelRef = str;
					}
				}
				if (modelRef == null) {
					modelRef = firstModelName;
				}
				AgentConfig agent = new AgentConfig();
				agent.setModel(modelRef);
				if (AgentRole.CODING.equals(role)) {
					agent.setSkilldir(existingSkillDir);
				}
				newAgents.put(role, agent);
			}
			candidate.setAgents(newAgents);

			ModelResolver.validateForSave(candidate);
			businessConfigService.set(candidate, user.getDomainId());

			ModelResolver.normalizeForRead(candidate.getModels());
			JSONObject data = new JSONObject();
			data.put("models", candidate.getModels());
			data.put("agents", candidate.getAgents());
			ret.put("data", data);
			ret.put("success", true);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			ret.put("success", false);
			ret.put("message", e.getMessage());
		}
		return ret;
	}

	@PostMapping("/businessConfig/saveToolSkillDir")
	@ResponseBody
	public JSONObject saveToolSkillDir(@RequestBody JSONObject input) {
		JSONObject ret = new JSONObject();
		try {
			User user = SystemUtils.getCurUser();
			Object raw = input.get("skilldir");
			if (!(raw instanceof String skilldir)) {
				throw new IllegalArgumentException("skilldir must be a string");
			}
			BusinessConfig businessConfig = businessConfigService.get(user.getDomainId());
			businessConfig.getAgents().get(AgentRole.CODING).setSkilldir(skilldir);
			businessConfigService.set(businessConfig, user.getDomainId());
			ret.put("success", true);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			ret.put("success", false);
			ret.put("message", e.getMessage());
		}
		return ret;
	}
	
	@PostMapping("/businessConfig/testEmbeddingModel")
    @ResponseBody
    public JSONObject testEmbeddingModel(@RequestBody JSONObject input) {
    	JSONObject ret = new JSONObject();
    	try {
    		EmbeddingModelProperties embeddingModelProperties = JSONObject.parseObject(JSON.toJSONString(input.getJSONObject("modelConfig")), EmbeddingModelProperties.class);
    		if (embeddingModelProperties == null || embeddingModelProperties.getDimensions() == null
    				|| embeddingModelProperties.getDimensions() <= 0) {
    			throw new IllegalArgumentException("modelConfig.dimensions must be a positive integer");
    		}
    		embeddingModelProperties.setLogRequests(true);
    		embeddingModelProperties.setLogResponses(true);
    		String userContent = input.getString("userContent");
    		if (userContent == null || "".equals(userContent.trim())) {
    			userContent = "Hello";
    		}
    		JSONObject aiContent = embeddingService.testEmbeddingModel(embeddingModelProperties, userContent);
    		ret.put("data", aiContent);
    		ret.put("success", true);
    	} catch (Exception e) {
    		log.error(e.getMessage(), e);
    		ret.put("success", false);
    		ret.put("message", e.getMessage());
    	}
		return ret;
	}
	
	@PostMapping("/businessConfig/saveEmbeddingModel")
    @ResponseBody
    public JSONObject saveEmbeddingModel(@RequestHeader(ControllerConst.LANG_HEADER_KEY) String lang, @RequestBody JSONObject input) {
    	JSONObject ret = new JSONObject();
    	try {
    		User user = SystemUtils.getCurUser();
    		EmbeddingModelProperties embeddingModelProperties = JSONObject.parseObject(JSON.toJSONString(input), EmbeddingModelProperties.class);
    		if (embeddingModelProperties == null || embeddingModelProperties.getDimensions() == null
    				|| embeddingModelProperties.getDimensions() <= 0) {
    			throw new IllegalArgumentException("dimensions must be a positive integer");
    		}
			vectorRebuildService.saveEmbeddingModel(user.getDomainId(), lang, embeddingModelProperties);
    		ret.put("success", true);
    	} catch (Exception e) {
    		log.error(e.getMessage(), e);
    		ret.put("success", false);
    		ret.put("message", e.getMessage());
    	}
		return ret;
	}

	@GetMapping("/businessConfig/vectorRebuildStatus")
    @ResponseBody
    public JSONObject vectorRebuildStatus() {
    	JSONObject ret = new JSONObject();
    	try {
    		User user = SystemUtils.getCurUser();
    		VectorRebuildStatus status = vectorRebuildService.status(user.getDomainId());
    		ret.put("data", status);
    		ret.put("success", true);
    	} catch (Exception e) {
    		log.error(e.getMessage(), e);
    		ret.put("success", false);
    		ret.put("message", e.getMessage());
    	}
		return ret;
	}

	@PostMapping("/businessConfig/rebuildVectors")
    @ResponseBody
    public JSONObject rebuildVectors(@RequestHeader(ControllerConst.LANG_HEADER_KEY) String lang) {
    	JSONObject ret = new JSONObject();
    	try {
    		User user = SystemUtils.getCurUser();
    		vectorRebuildService.start(user.getDomainId(), lang);
    		ret.put("success", true);
    	} catch (Exception e) {
    		log.error(e.getMessage(), e);
    		ret.put("success", false);
    		ret.put("message", e.getMessage());
    	}
		return ret;
	}
	
    @PostMapping("/businessConfig/pauseVectorRebuild")
    @ResponseBody
    public JSONObject pauseVectorRebuild(@RequestHeader(ControllerConst.LANG_HEADER_KEY) String lang) {
        JSONObject ret = new JSONObject();
        try {
            User user = SystemUtils.getCurUser();
            vectorRebuildService.pause(user.getDomainId(), lang);
            ret.put("success", true);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            ret.put("success", false);
            ret.put("message", e.getMessage());
        }
        return ret;
    }

	@PostMapping("/businessConfig/saveKnowledgeMaxResult")
    @ResponseBody
    public JSONObject saveKnowledgeMaxResult(@RequestBody JSONObject input) {
    	JSONObject ret = new JSONObject();
    	try {
    		User user = SystemUtils.getCurUser();
    		Integer knowledgeMaxResult = input.getInteger("knowledgeMaxResult");
    		if (knowledgeMaxResult != null) {
    			BusinessConfig businessConfig = businessConfigService.get(user.getDomainId());
        		businessConfig.setKnowledgeMaxResult(knowledgeMaxResult);
        		businessConfigService.set(businessConfig, user.getDomainId());
    		}
    		ret.put("success", true);
    	} catch (Exception e) {
    		log.error(e.getMessage(), e);
    		ret.put("success", false);
    		ret.put("message", e.getMessage());
    	}
		return ret;
	}
	
	@PostMapping("/businessConfig/saveToolAndPythonRetry")
    @ResponseBody
    public JSONObject saveToolAndPythonRetry(@RequestBody JSONObject input) {
    	JSONObject ret = new JSONObject();
    	try {
    		User user = SystemUtils.getCurUser();
    		Integer toolAndPythonRetry = input.getInteger("toolAndPythonRetry");
    		if (toolAndPythonRetry != null) {
    			BusinessConfig businessConfig = businessConfigService.get(user.getDomainId());
        		businessConfig.setToolAndPythonRetry(toolAndPythonRetry);
        		businessConfigService.set(businessConfig, user.getDomainId());
    		}
    		ret.put("success", true);
    	} catch (Exception e) {
    		log.error(e.getMessage(), e);
    		ret.put("success", false);
    		ret.put("message", e.getMessage());
    	}
		return ret;
	}
	
	@PostMapping("/businessConfig/saveDslCookerTries")
    @ResponseBody
    public JSONObject saveDslCookerTries(@RequestBody JSONObject input) {
    	JSONObject ret = new JSONObject();
    	try {
    		User user = SystemUtils.getCurUser();
    		Integer dslCookerTries = input.getInteger("dslCookerTries");
    		if (dslCookerTries != null && dslCookerTries > 0) {
    			BusinessConfig businessConfig = businessConfigService.get(user.getDomainId());
        		businessConfig.setDslCookerTries(dslCookerTries);
        		businessConfigService.set(businessConfig, user.getDomainId());
    		}
    		ret.put("success", true);
    	} catch (Exception e) {
    		log.error(e.getMessage(), e);
    		ret.put("success", false);
    		ret.put("message", e.getMessage());
    	}
		return ret;
	}
	
	@PostMapping("/businessConfig/saveDslCookerTimeout")
    @ResponseBody
    public JSONObject saveDslCookerTimeout(@RequestBody JSONObject input) {
    	JSONObject ret = new JSONObject();
    	try {
    		User user = SystemUtils.getCurUser();
    		Long dslCookerTimeout = input.getLong("dslCookerTimeout");
    		if (dslCookerTimeout != null && dslCookerTimeout > 0) {
    			BusinessConfig businessConfig = businessConfigService.get(user.getDomainId());
        		businessConfig.setDslCookerTimeout(dslCookerTimeout);
        		businessConfigService.set(businessConfig, user.getDomainId());
    		}
    		ret.put("success", true);
    	} catch (Exception e) {
    		log.error(e.getMessage(), e);
    		ret.put("success", false);
    		ret.put("message", e.getMessage());
    	}
		return ret;
	}
	
	@PostMapping("/businessConfig/saveQuestionSpliterTries")
    @ResponseBody
    public JSONObject saveQuestionSpliterTries(@RequestBody JSONObject input) {
    	JSONObject ret = new JSONObject();
    	try {
    		User user = SystemUtils.getCurUser();
    		Integer questionSpliterTries = input.getInteger("questionSpliterTries");
    		if (questionSpliterTries != null && questionSpliterTries > 0) {
    			BusinessConfig businessConfig = businessConfigService.get(user.getDomainId());
        		businessConfig.setQuestionSpliterTries(questionSpliterTries);
        		businessConfigService.set(businessConfig, user.getDomainId());
    		}
    		ret.put("success", true);
    	} catch (Exception e) {
    		log.error(e.getMessage(), e);
    		ret.put("success", false);
    		ret.put("message", e.getMessage());
    	}
		return ret;
	}
	
	@PostMapping("/businessConfig/saveQuestionSpliterTimeout")
    @ResponseBody
    public JSONObject saveQuestionSpliterTimeout(@RequestBody JSONObject input) {
    	JSONObject ret = new JSONObject();
    	try {
    		User user = SystemUtils.getCurUser();
    		Long questionSpliterTimeout = input.getLong("questionSpliterTimeout");
    		if (questionSpliterTimeout != null && questionSpliterTimeout > 0) {
    			BusinessConfig businessConfig = businessConfigService.get(user.getDomainId());
        		businessConfig.setQuestionSpliterTimeout(questionSpliterTimeout);
        		businessConfigService.set(businessConfig, user.getDomainId());
    		}
    		ret.put("success", true);
    	} catch (Exception e) {
    		log.error(e.getMessage(), e);
    		ret.put("success", false);
    		ret.put("message", e.getMessage());
    	}
		return ret;
	}
	
	@PostMapping("/businessConfig/saveLang")
    @ResponseBody
    public JSONObject saveLang(@RequestBody JSONObject input) {
    	JSONObject ret = new JSONObject();
    	try {
    		User user = SystemUtils.getCurUser();
    		String lang = input.getString("lang");
    		if (lang != null) {
    			lang = lang.trim();
    		}
    		BusinessConfig businessConfig = businessConfigService.get(user.getDomainId());
    		businessConfig.setLang(lang);
    		businessConfigService.set(businessConfig, user.getDomainId());
    		ret.put("success", true);
    	} catch (Exception e) {
    		log.error(e.getMessage(), e);
    		ret.put("success", false);
    		ret.put("message", e.getMessage());
    	}
		return ret;
	}
	
	@GetMapping("/businessConfig/dataAdapters")
    @ResponseBody
    public JSONObject dataAdapters() {
		User user = SystemUtils.getCurUser();
		JSONArray adapters = new JSONArray();
    	for (DataAdapterProvider provider : dataAdapterRegistry.installed()) {
    		JSONObject item = new JSONObject();
    		item.put("type", provider.type());
    		item.put("label", provider.label());
			item.put("requiresJoinFields", provider.requiresJoinFields());
    		item.put("fields", provider.connectionFields());
    		item.put("examples", provider.connectionExamples());
			adapters.add(item);
    	}
		// The current domain's adapter type without its saved connections: what non-management readers need.
		JSONObject data = new JSONObject();
		data.put("currentType", businessConfigService.get(user.getDomainId()).getDataAdapter());
		data.put("adapters", adapters);
    	JSONObject ret = new JSONObject();
    	ret.put("data", data);
    	ret.put("success", true);
		return ret;
	}

	/** Preview the saved business source schema; this never changes the source backend. */
	@GetMapping("/businessConfig/adapterOntology")
	public JSONObject adapterOntology() {
		JSONObject ret = new JSONObject();
		try {
			BusinessConfig config = businessConfigService.get(SystemUtils.getCurUser().getDomainId());
			DataAdapter adapter = dataAdapterRegistry.create(config, null);
			adapter.capabilities().requireSchemaRead();
			JSONObject ontology = prettyOntology(adapter.readOntology());
			ret.put("data", JSONObject.of("type", config.getDataAdapter(), "ontology", ontology,
					"connectionFingerprint", connectionFingerprint(config)));
			ret.put("success", true);
		} catch (Exception e) {
			ret.put("success", false); ret.put("message", e.getMessage());
		}
		return ret;
	}

	/** Only imports the current backend schema into platform ontology metadata. */
	@PostMapping("/businessConfig/importAdapterOntology")
	public JSONObject importAdapterOntology(@RequestBody JSONObject input) {
		JSONObject ret = new JSONObject();
		try {
			String domain = SystemUtils.getCurUser().getDomainId();
			BusinessConfig config = businessConfigService.get(domain);
			if (!config.getDataAdapter().equals(input.getString("type"))
					|| !connectionFingerprint(config).equals(input.getString("connectionFingerprint")))
				throw new IllegalArgumentException("Saved data source changed; preview its schema again");
			DataAdapter adapter = dataAdapterRegistry.create(config, null);
			adapter.capabilities().requireSchemaRead();
			JSONObject current = prettyOntology(adapter.readOntology());
			// Import the reviewed definition. Admin revalidates its structure against the live schema.
			JSONObject reviewed = input.getJSONObject("ontology");
			if (reviewed == null || !current.equals(reviewed))
				throw new IllegalArgumentException("Backend schema changed; preview its schema again");
			adminService.setPrettyJSONRule(current, domain);
			ret.put("data", current); ret.put("success", true);
		} catch (Exception e) {
			ret.put("success", false); ret.put("message", e.getMessage());
		}
		return ret;
	}

	private static String connectionFingerprint(BusinessConfig config) {
		DataAdapterConnection connection = config.getDataAdapterConnections().get(config.getDataAdapter());
		String content = config.getDataAdapter() + "\n" + JSON.toJSONString(connection);
		try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
				.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
		catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
	}

	private static JSONObject prettyOntology(Map<String, Object> raw) {
		JSONObject source = JSONObject.from(raw), pretty = new JSONObject();
		pretty.put("datasetDesc", source.get("datasetdesc")); pretty.put("classList", source.get("classlist"));
		JSONArray classes = new JSONArray();
		for (Object value : source.getJSONArray("classDef")) {
			JSONObject cls = JSONObject.from(value);
			JSONObject out = JSONObject.of("name", cls.get("className"), "showName", cls.get("showName"), "desc", cls.get("classDesc"),
					"classToCard", cls.get("classToCard"), "instanceToCard", cls.get("instanceToCard"), "inStarChart", cls.get("inStarChart"));
			JSONArray attrs = new JSONArray();
			for (Object attribute : cls.getJSONArray("attrs")) {
				JSONObject a = JSONObject.from(attribute);
				JSONObject attr = JSONObject.of("name", a.get("name"), "showName", a.get("showName"), "desc", a.get("attrDesc"), "type", a.get("type"),
						"bizzKey", a.get("bizzkey"), "enable", a.get("enable"), "permissionField", a.get("permissionField"), "primaryKey", a.get("primaryKey"), "sourceName", a.get("sourceName"));
				if (a.get("indicators") != null) {
					JSONArray indicators = new JSONArray();
					for (Object indicator : a.getJSONArray("indicators")) {
						JSONObject i = JSONObject.from(indicator);
						indicators.add(JSONObject.of("name", i.get("name"), "desc", i.get("desc"), "unit", i.get("unit")));
					}
					attr.put("indicators", indicators);
				}
				attrs.add(attr);
			}
			out.put("attrs", attrs); classes.add(out);
		}
		pretty.put("classDefs", classes); JSONObject relationships = new JSONObject();
		JSONObject rules = source.getJSONObject("relationship_rule");
		for (String name : rules.keySet()) {
			JSONObject r = rules.getJSONObject(name);
			relationships.put(name, JSONObject.of("fromClass", r.get("fromclass"), "toClass", r.get("toclass"), "desc", r.get("desc"),
					"fromField", r.get("fromField"), "toField", r.get("toField")));
		}
		pretty.put("relationshipDefs", relationships);
		// Match the JSON sent to the browser: serialization omits null-valued fields.
		return JSON.parseObject(pretty.toJSONString());
	}
	
	@PostMapping("/businessConfig/useDataAdapter")
    @ResponseBody
    public JSONObject useDataAdapter(@RequestBody JSONObject input) {
    	JSONObject ret = new JSONObject();
    	try {
    		User user = SystemUtils.getCurUser();
    		DataAdapterProvider provider = dataAdapterRegistry.provider(input.getString("type"));
    		if (MULTI.equals(provider.type())) {
    			throw new IllegalArgumentException("The multi data adapter is saved through /businessConfig/useMultiDataSource");
    		}
    		BusinessConfig businessConfig = businessConfigService.get(user.getDomainId());
    		businessConfig.getDataAdapterConnections().put(provider.type(), connectionOf(provider, input));
    		businessConfig.setDataAdapter(provider.type());
    		businessConfigService.set(businessConfig, user.getDomainId());
    		ret.put("success", true);
    	} catch (Exception e) {
    		log.error(e.getMessage(), e);
    		ret.put("success", false);
    		ret.put("message", e.getMessage());
    	}
		return ret;
	}

	/**
	 * Switch the current domain to the multi data adapter with the given definition.
	 *
	 * <p>The definition is checked on its own and against the domain's ontomap under the multi adapter's
	 * rules; nothing is saved unless both pass, and a failure lists every located problem. Each source keeps
	 * only the connection fields its type declares. The single-source connections are left untouched.</p>
	 */
	@PostMapping("/businessConfig/useMultiDataSource")
    @ResponseBody
    public JSONObject useMultiDataSource(@RequestBody JSONObject input) {
    	JSONObject ret = new JSONObject();
    	try {
    		User user = SystemUtils.getCurUser();
    		MultiDataSourceDefinition definition = input.to(MultiDataSourceDefinition.class);
    		List<ConfigurationProblem> problems = MultiDataSourceValidator.problems(definition, dataAdapterRegistry);
    		if (problems.isEmpty()) {
    			for (DataSourceEntry source : definition.getDataSources()) {
    				DataAdapterConnection given = source.getConnection();
    				source.setConnection(connectionOf(dataAdapterRegistry.provider(source.getAdapterType()),
    						given == null ? new JSONObject() : JSONObject.from(given)));
    			}
    			BusinessConfig businessConfig = businessConfigService.get(user.getDomainId());
    			businessConfig.setMultiDataSource(definition);
    			businessConfig.setDataAdapter(MULTI);
    			Map<String, Object> jsonRule = adminService.getJSONRule(user.getDomainId());
    			problems = dataAdapterRegistry.create(businessConfig, jsonRule).validateOntoMap(jsonRule);
    			if (problems.isEmpty()) {
    				businessConfigService.set(businessConfig, user.getDomainId());
    				ret.put("success", true);
    				return ret;
    			}
    		}
    		ret.put("success", false);
    		ret.put("message", "Invalid multi-source configuration: "
    				+ String.join("; ", problems.stream().map(ConfigurationProblem::detail).toList()));
    		ret.put("data", JSONObject.of("problems", problems));
    	} catch (Exception e) {
    		log.error(e.getMessage(), e);
    		ret.put("success", false);
    		ret.put("message", e.getMessage());
    	}
		return ret;
	}

	@PostMapping("/businessConfig/testDataAdapter")
    @ResponseBody
    public JSONObject testDataAdapter(@RequestBody JSONObject input) {
    	JSONObject ret = new JSONObject();
    	try {
    		DataAdapterProvider provider = dataAdapterRegistry.provider(input.getString("type"));
    		// The candidate values are tested as given; nothing saved is read or written.
    		DataAdapter adapter = dataAdapterRegistry.createForTest(provider.type(), connectionOf(provider, input));
    		ret.put("data", adapter.testConnection());
    		ret.put("success", true);
    	} catch (Exception e) {
    		log.error(e.getMessage(), e);
    		ret.put("success", false);
    		ret.put("message", DataAdapterConnectionTester.failureMessage(e));
    	}
		return ret;
	}

	/** The request values of the fields this provider declares; undeclared fields are ignored. */
	private static DataAdapterConnection connectionOf(DataAdapterProvider provider, JSONObject input) {
		List<String> fields = provider.connectionFields();
		DataAdapterConnection connection = new DataAdapterConnection();
		if (fields.contains("url")) {
			connection.setUrl(input.getString("url"));
		}
		if (fields.contains("company")) {
			connection.setCompany(input.getString("company"));
		}
		if (fields.contains("user")) {
			connection.setUser(input.getString("user"));
		}
		if (fields.contains("password")) {
			connection.setPassword(input.getString("password"));
		}
		if (fields.contains("token")) {
			connection.setToken(input.getString("token"));
		}
		return connection;
	}
	
	@GetMapping("/businessConfig/getConfig")
    @ResponseBody
    public JSONObject getConfig() {
    	JSONObject ret = new JSONObject();
    	try {
    		User user = SystemUtils.getCurUser();
			ret.put("data", businessConfigService.get(user.getDomainId()));
    		ret.put("success", true);
    	} catch (Exception e) {
    		log.error(e.getMessage(), e);
    		ret.put("success", false);
    		ret.put("message", e.getMessage());
    	}
		return ret;
	}
	
	@GetMapping("/businessConfig/getConfigAndDesc")
    @ResponseBody
    public JSONObject getConfigAndDesc() {
    	JSONObject ret = new JSONObject();
    	try {
    		User user = SystemUtils.getCurUser();
    		JSONObject data = new JSONObject();
			data.put("config", businessConfigService.getDiagnosticConfig(user.getDomainId()));
    		data.put("desc", businessConfigService.getWholeConfigDesc());
    		ret.put("data", data);
    		ret.put("success", true);
    	} catch (Exception e) {
    		log.error(e.getMessage(), e);
    		ret.put("success", false);
    		ret.put("message", e.getMessage());
    	}
		return ret;
	}
	
}

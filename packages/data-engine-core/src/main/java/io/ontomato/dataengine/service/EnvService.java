package io.ontomato.dataengine.service;

import java.io.InputStream;
import java.util.List;

import com.alibaba.fastjson2.JSONObject;
import io.ontomato.dataengine.core.bean.User;

public interface EnvService {

	public void importSchema(User user) throws Exception;
	
	public void importTestData(InputStream is, String domainId) throws Exception;
	
	public JSONObject executeDslInProductionEnv(JSONObject dsl) throws Exception;
	
	public String queryDistinctAttrValueInProductionEnv(String className, String attrName, String like, int limit) throws Exception;
	
	public String queryBusinessKnowledgeInProductionEnv(String question, Integer max_result) throws Exception;

	public boolean isProductionEnvConfigured();

	public JSONObject syncAssetsToProduction(List<String> actionIds, List<String> metricViewIds, boolean dryRun) throws Exception;
	
}

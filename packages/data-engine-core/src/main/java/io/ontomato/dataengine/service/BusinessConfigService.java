package io.ontomato.dataengine.service;

import com.alibaba.fastjson2.JSONObject;
import io.ontomato.dataengine.config.BusinessConfig;

public interface BusinessConfigService {

	/** Save the whole config; an existing domain keeps its stored embedding configuration. */
	public void set(BusinessConfig businessConfig, String domainId);

	/** Replace only the embedding configuration; called by the vector rebuild service alone. */
	public void setEmbeddingModel(BusinessConfig.EmbeddingModelProperties properties, String domainId);
	
	public BusinessConfig get(String domainId);
	
	public JSONObject getRuntimeWholeConfig(String domainId);

	/** The diagnosis view of the running configuration: named non-secret fields only, never keys, passwords or raw headers. */
	public JSONObject getDiagnosticConfig(String domainId);
	
	public String getWholeConfigDesc();
	
}

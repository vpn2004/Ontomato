package io.ontomato.dataengine.dao;

import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter.Feature;
import io.ontomato.dataengine.config.AgentConfig;
import io.ontomato.dataengine.config.BusinessConfig;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component("businessConfigDao")
public class BusinessConfigDao {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private static final String TABLE_NAME = "business_config";

	private static final String EMBEDDING_KEY = "embeddingModelProperties";

	@PostConstruct
	public void initIndex() {
		jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " ("
				+ "id VARCHAR(64) PRIMARY KEY, "
				+ "content TEXT"
				+ ")");
		log.info(">>>businessConfig table initialization complete");
	}

	/**
	 * Save the whole record. A stored config keeps its embedding configuration: it changes only through
	 * {@link #saveEmbeddingModel}, so a save built from an older snapshot cannot write an old embedding
	 * generation back. A new row, or one whose content is SQL or JSON null and is being seeded again by
	 * {@code BusinessConfigService.get}, takes the embedding configuration it is given.
	 */
	public void save(BusinessConfig businessConfig, String domainId) {
		String stored = TABLE_NAME + ".content::jsonb";
		jdbcTemplate.update(
				"INSERT INTO " + TABLE_NAME + " (id, content) VALUES (?, ?) "
						+ "ON CONFLICT (id) DO UPDATE SET content = CASE WHEN jsonb_typeof(" + stored + ") = 'object' "
						+ "THEN jsonb_set(EXCLUDED.content::jsonb, '{" + EMBEDDING_KEY + "}', "
						+ "COALESCE(" + stored + " -> '" + EMBEDDING_KEY + "', 'null'::jsonb))::text "
						+ "ELSE EXCLUDED.content END",
				domainId, JSON.toJSONString(businessConfig, Feature.WriteMapNullValue));
	}

	/** Replace only the embedding configuration of an existing record; the vector rebuild service owns this write. */
	public void saveEmbeddingModel(BusinessConfig.EmbeddingModelProperties properties, String domainId) {
		int updated = jdbcTemplate.update(
				"UPDATE " + TABLE_NAME + " SET content = jsonb_set(content::jsonb, '{" + EMBEDDING_KEY + "}', ?::jsonb)::text WHERE id = ?",
				JSON.toJSONString(properties, Feature.WriteMapNullValue), domainId);
		if (updated == 0) {
			throw new IllegalStateException("business_config record not found (domainId=" + domainId + ")");
		}
	}

	public BusinessConfig query(String domainId) {
		try {
			String content = jdbcTemplate.queryForObject(
					"SELECT content FROM " + TABLE_NAME + " WHERE id = ?",
					String.class, domainId);
			if (content == null) {
				return null;
			}
			JSONObject stored = JSON.parseObject(content, JSONReader.Feature.DisableReferenceDetect);
			if (stored == null) {
				return null;
			}
			if (!stored.containsKey("models")) {
				throw new IllegalStateException(
						"business_config record is in the old format; run the upgrade migration first (domainId=" + domainId + ")");
			}
			rejectLegacyShapes(stored, domainId);
			if (LegacyDataAdapterConnections.migrate(stored)) {
				content = JSON.toJSONString(stored, Feature.WriteMapNullValue);
				jdbcTemplate.update("UPDATE " + TABLE_NAME + " SET content = ? WHERE id = ?", content, domainId);
				log.info("business_config data adapter connections migrated (domainId={})", domainId);
			}
			return JSON.parseObject(
					content, BusinessConfig.class, JSONReader.Feature.DisableReferenceDetect);
		} catch (org.springframework.dao.EmptyResultDataAccessException e) {
			return null;
		} catch (RuntimeException e) {
			log.error("Failed to read BusinessConfig from PostgreSQL", e);
			throw e;
		}
	}

	private static final Set<String> LEGACY_ROLE_KEYS = Set.of(
			"normalChat", "questionSpliter", "cleverNormalChat", "pythonChat", "toolChat");

	static void rejectLegacyShapes(JSONObject stored, String domainId) {
		JSONObject agentsObj = stored.getJSONObject("agents");
		if (agentsObj != null) {
			for (String oldKey : LEGACY_ROLE_KEYS) {
				if (agentsObj.containsKey(oldKey)) {
					throw new IllegalStateException(
							"business_config record contains legacy role key " + oldKey
									+ "; run the upgrade migration first (domainId=" + domainId + ")");
				}
			}
			for (Map.Entry<String, Object> entry : agentsObj.entrySet()) {
				if (entry.getValue() instanceof Map<?, ?> roleMap) {
					if (roleMap.containsKey("actorCopies")) {
						throw new IllegalStateException(
								"business_config record contains legacy agent parameter actorCopies in role "
										+ entry.getKey() + "; run the upgrade migration first (domainId=" + domainId + ")");
					}
					for (String legacy : AgentConfig.LEGACY_MODEL_FIELDS) {
						if (roleMap.containsKey(legacy)) {
							throw new IllegalStateException(
									"business_config record still contains role-side model parameters (" + legacy
											+ " in role " + entry.getKey()
											+ "); run the upgrade migration first (domainId=" + domainId + ")");
						}
					}
				}
			}
		}

		JSONArray modelsArr = stored.getJSONArray("models");
		if (modelsArr != null) {
			for (int i = 0; i < modelsArr.size(); i++) {
				Object item = modelsArr.get(i);
				if (item instanceof Map<?, ?> modelMap) {
					if (modelMap.containsKey("strictTools") || modelMap.containsKey("strict_tools") || modelMap.containsKey("strict-tools")) {
						throw new IllegalStateException(
								"business_config record contains legacy model parameter strictTools; run the upgrade migration first (domainId="
										+ domainId + ")");
					}
				}
			}
		}
	}
}

package io.ontomato.dataengine.dao;

import java.util.Objects;

import io.ontomato.dataengine.config.BusinessConfig;

import lombok.Data;

/**
 * The persisted vector state of one domain: the generation (baseUrl, modelName, dimensions) its
 * vectors are meant to belong to, whether the current round has cleared every vector of another
 * generation, and how the latest round ended. Rows whose vector is NULL are the pending work.
 */
@Data
public class VectorRebuildState {

    public enum Status {
        /** Every vector belongs to the generation; retrieval is allowed. */
        READY,
        /** A round is needed and has not been started for this generation. */
        PENDING,
        /** A round was started; without a live run in this process it was interrupted. */
        RUNNING,
        PAUSED,
        FAILED
    }

    public enum Phase {
        CLEAR,
        /** Reading the pending rows of one item type; no single item is involved. */
        READ,
        EMBED, SAVE
    }

    public enum ItemType {
        KNOWLEDGE, EXAMPLE, SPLIT_EXAMPLE, ACTION, METRIC_VIEW, RESOURCE
    }

    /** Where the latest round failed and why; the reason never carries the key or header values. */
    @Data
    public static class Failure {
        private Phase phase;
        /** Null in the CLEAR phase, which covers the whole domain. */
        private ItemType itemType;
        /** Null in the CLEAR and READ phases. */
        private String itemId;
        private String reason;
    }

    private String baseUrl;
    private String modelName;
    private Integer dimensions;
    private Status status;
    /** True once no vector of another generation remains; always true when READY. */
    private boolean cleared;
    private Failure error;

    public static VectorRebuildState of(BusinessConfig.EmbeddingModelProperties generation, Status status,
            boolean cleared) {
        VectorRebuildState state = new VectorRebuildState();
        state.setBaseUrl(generation.getBaseUrl());
        state.setModelName(generation.getModelName());
        state.setDimensions(generation.getDimensions());
        state.setStatus(status);
        state.setCleared(cleared);
        return state;
    }

    /** baseUrl, modelName and dimensions identify generated vectors; the key and timeout never count. */
    public boolean sameGeneration(BusinessConfig.EmbeddingModelProperties properties) {
        return Objects.equals(baseUrl, properties.getBaseUrl())
                && Objects.equals(modelName, properties.getModelName())
                && Objects.equals(dimensions, properties.getDimensions());
    }

}

package io.ontomato.dataengine.dataAdapter;

/**
 * Default output cap and shared completeness reporting for output or intermediate truncation.
 *
 * <p>The cap is a DSL-level semantic — it bounds what the engine returns to the caller — so it is
 * defined once here instead of as a per-adapter string literal. An omitted limit is <em>not</em>
 * "no bound": it means "apply the engine default". Whenever that default truncates, the adapter
 * marks the answer, so no caller can mistake a capped result for a complete one.</p>
 *
 * <p>This complements {@link DslContractViolationException}: an unrenderable construct is rejected,
 * while a defaulted-and-hit cap is a successful but incomplete result that is reported, never silent.</p>
 */
public final class DslRowLimit {

	/** Default number of rows a terminal step returns when it omits {@code output.limit}. */
	public static final int DEFAULT_ROW_LIMIT = 20000;

	/** Answer key: {@code true} when an output cap or an incomplete input limits the result. */
	public static final String TRUNCATED = "truncated";
	/** Answer key: the cap that was hit (present only when {@link #TRUNCATED} is true). */
	public static final String ROW_LIMIT = "rowLimit";
	/** Answer key: an actionable sentence for the planner when truncation happened. */
	public static final String TRUNCATION_HINT = "truncationHint";

	private DslRowLimit() {
	}

	/**
	 * The effective default row cap. Overridable per deployment — and by tests — through the
	 * {@code ontomato.dsl.defaultRowLimit} system property; read per query so a test can shrink it
	 * without touching adapter state.
	 */
	public static int defaultRowLimit() {
		int configured = Integer.getInteger("ontomato.dsl.defaultRowLimit", DEFAULT_ROW_LIMIT);
		return configured > 0 ? configured : DEFAULT_ROW_LIMIT;
	}

	/** The hint appended to a truncated answer. */
	public static String truncationHint(int limit) {
		return "The result was capped at the default limit of " + limit
				+ " rows and is incomplete; add an explicit output.limit or aggregate before relying on it.";
	}

	/**
	 * Per-call state for terminal limits and the first actual truncation. A later aggregate or smaller
	 * output must not erase the fact that its input was incomplete.
	 */
	public static final class Tracker {
		private int effectiveLimit = -1;
		private boolean defaulted;
		private boolean truncated;
		private int truncatedLimit;
		private String hint;

		/** Record the cap applied to the terminal step. {@code defaulted} = it was the engine default. */
		public void terminalLimit(int limit, boolean defaulted) {
			this.effectiveLimit = limit;
			this.defaulted = defaulted;
		}

		/** Mark that the defaulted cap actually dropped rows. */
		public void markTruncated() {
			markTruncated(effectiveLimit, DslRowLimit.truncationHint(effectiveLimit));
		}

		/** Keep the first incomplete input fact when a later projection also reaches its output cap. */
		public void markTruncated(int limit, String hint) {
			if (!truncated) {
				this.truncated = true;
				this.truncatedLimit = limit;
				this.hint = hint;
			}
		}

		public int truncatedLimit() {
			return truncatedLimit;
		}

		public String hint() {
			return hint;
		}

		public boolean defaulted() {
			return defaulted;
		}

		public int effectiveLimit() {
			return effectiveLimit;
		}

		public boolean truncated() {
			return truncated;
		}
	}
}

package io.ontomato.dataengine.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Stable content hash for action code, used to decide whether a stored explanation still matches. */
public final class CodeHashUtil {

	private CodeHashUtil() {
	}

	public static String sha256(String code) {
		if (code == null) {
			return null;
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest(code.getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(bytes.length * 2);
			for (byte value : bytes) {
				builder.append(String.format("%02x", value));
			}
			return builder.toString();
		} catch (Exception e) {
			throw new RuntimeException("Cannot hash action code", e);
		}
	}
}

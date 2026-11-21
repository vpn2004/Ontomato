package io.ontomato.dataengine.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Minimal single-entry zip helper used by the full export/import endpoints.
 */
public final class ZipBundleUtil {

	private ZipBundleUtil() {
	}

	public static byte[] zip(String entryName, byte[] content) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zos = new ZipOutputStream(out)) {
			zos.putNextEntry(new ZipEntry(entryName));
			zos.write(content);
			zos.closeEntry();
		}
		return out.toByteArray();
	}

	public static byte[] unzipFirstJson(byte[] zipData) throws IOException {
		try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".json")) {
					return zis.readAllBytes();
				}
			}
		}
		throw new IOException("No JSON file in zip");
	}
}

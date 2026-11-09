package io.ontomato.dataengine.dataAdapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/** A fresh, deterministic schema projection; never mixes /schema/meta and /schema/all hashes. */
final class OnnCoreSchema {
    private final Map<String, JSONObject> classes = new TreeMap<>();
    private final Map<String, Map<String, JSONObject>> attributes = new TreeMap<>();
    private final Map<String, Map<String, String>> sourceNames = new TreeMap<>();
    private final Map<String, Object> ontology;
    private final Map<String, JSONObject> mappingClasses = new HashMap<>();
    private final Map<String, JSONObject> mappingRelations = new HashMap<>();
    final String fingerprint;

    OnnCoreSchema(JSONObject wire) {
        JSONArray defs = wire.getJSONArray("ontologies"), relations = wire.getJSONArray("relations");
        if (!wire.containsKey("ontologies") || !wire.containsKey("relations"))
            throw new IllegalArgumentException("ONN schema response requires ontologies and relations");
        // Go serializes empty collections as null; absent keys still indicate an incomplete response.
        if (defs == null) defs = new JSONArray();
        if (relations == null) relations = new JSONArray();
        for (int i = 0; i < defs.size(); i++) {
            JSONObject def = defs.getJSONObject(i);
            String path = def.getString("path");
            validatePath(path);
            if (classes.put(path, def) != null) throw new IllegalArgumentException("Duplicate ONN class " + path);
        }
        List<Map<String, Object>> classDefs = new ArrayList<>();
        for (String path : classes.keySet()) {
            Map<String, JSONObject> effective = resolve(path, new HashSet<>());
            attributes.put(path, effective);
            Map<String, String> names = new LinkedHashMap<>();
            List<Map<String, Object>> attrs = new ArrayList<>();
            attrs.add(attr("id", "varchar", "ONN instance identity (top-level id)", true, true, "@identity"));
            for (Map.Entry<String, JSONObject> entry : effective.entrySet()) {
                String source = entry.getKey(), product = source;
                if (source.equals("id")) {
                    product = "onn_attr_id";
                    while (effective.containsKey(product)) product += "_";
                }
                names.put(product, source);
                JSONObject def = entry.getValue();
                String type = switch (Objects.toString(def.getString("dataType"), "")) {
                    case "string" -> "varchar";
                    case "int" -> "int";
                    case "float" -> "float";
                    case "boolean" -> "boolean";
                    case "datetime" -> "datetime";
                    // Preserve unsupported metadata for review, without inventing scalar semantics.
                    default -> "varchar";
                };
                boolean supported = scalarSupported(def);
                String desc = Objects.toString(def.getString("description"), "");
                if (!supported) desc += " (ONN type=" + Objects.toString(def.getString("dataType"), "unspecified")
                    + (def.get("derived") == null ? "" : ", derived") + ": unavailable in this adapter)";
                attrs.add(attr(product, type, desc, false, supported, source));
            }
            sourceNames.put(path, names);
            JSONObject def = classes.get(path);
            Map<String, Object> projected = new LinkedHashMap<>();
            projected.put("className", path);
            projected.put("showName", Optional.ofNullable(def.getString("name")).filter(s -> !s.isBlank()).orElse(path));
            projected.put("classDesc", Objects.toString(def.getString("description"), ""));
            projected.put("classToCard", false); projected.put("instanceToCard", false); projected.put("inStarChart", false);
            projected.put("attrs", attrs);
            classDefs.add(projected);
        }
        Map<String, Map<String, String>> rules = new TreeMap<>();
        Set<String> relationPaths = new HashSet<>();
        List<String> unboundRelations = new ArrayList<>();
        for (int i = 0; i < relations.size(); i++) {
            JSONObject def = relations.getJSONObject(i);
            String path = def.getString("path"), from = def.getString("sourceClass"), to = def.getString("targetClass");
            validatePath(path);
            if (!relationPaths.add(path)) throw new IllegalArgumentException("Duplicate ONN relation: " + path);
            if (from == null || from.isBlank() || to == null || to.isBlank()) {
                // A polymorphic/unbound relation cannot become a typed Ontomato relationship.
                unboundRelations.add(path);
                continue;
            }
            if (!classes.containsKey(from) || !classes.containsKey(to)) throw new IllegalArgumentException("ONN relation has an unknown endpoint: " + path);
            Map<String, String> rule = new LinkedHashMap<>();
            rule.put("fromclass", from); rule.put("toclass", to); rule.put("fromField", "id"); rule.put("toField", "id");
            rule.put("desc", Objects.toString(def.getString("description"), ""));
            rule.put("fromdesc", ""); rule.put("todesc", "");
            rules.put(path, rule);
        }
        ontology = new LinkedHashMap<>();
        String description = "ONN Core business data (BASE view; typed relationship trees and read-only browsing)";
        if (!unboundRelations.isEmpty()) description += "; unbound relationships omitted: "
            + String.join(", ", unboundRelations.stream().sorted().toList());
        ontology.put("datasetdesc", description);
        ontology.put("classlist", new ArrayList<>(classes.keySet()));
        ontology.put("classDef", classDefs); ontology.put("relationship_rule", rules);
        for (Object raw : classDefs) { JSONObject def = JSONObject.from(raw); mappingClasses.put(def.getString("className"), def); }
        for (String type : rules.keySet()) mappingRelations.put(type, JSONObject.from(rules.get(type)));
        fingerprint = digest(JSON.toJSONString(canonical(ontology)));
    }

    Map<String, Object> ontology() { return ontology; }
    JSONObject mappingClass(String path) { requireClass(path); return mappingClasses.get(path); }
    JSONObject mappingRelationship(String type) { return mappingRelations.get(type); }
    void validateRelationship(Map<String, Object> mapping, String type, String from, String to) {
        JSONObject expected = JSONObject.from(ontology).getJSONObject("relationship_rule").getJSONObject(type);
        JSONObject rules = mapping == null ? null : JSONObject.from(mapping).getJSONObject("relationship_rule");
        JSONObject imported = rules == null ? null : rules.getJSONObject(type);
        if (expected == null || imported == null || Boolean.FALSE.equals(imported.getBoolean("enable")))
            throw new IllegalStateException("ONN relationship is unknown, disabled or not imported: " + type);
        for (String key : List.of("fromclass", "toclass", "fromField", "toField"))
            if (!Objects.equals(expected.getString(key), imported.getString(key)))
                throw new IllegalStateException("ONN relationship mapping changed; re-import schema: " + type);
        if (!from.equals(expected.getString("fromclass")) || !to.equals(expected.getString("toclass")))
            throw new IllegalArgumentException("ONN relationship direction or endpoints do not match: " + type);
    }
    void requireClass(String path) { if (!classes.containsKey(path)) throw new IllegalArgumentException("Unknown ONN class: " + path); }
    String sourceField(String path, String field) {
        requireClass(path);
        if ("id".equals(field)) return null;
        String source = sourceNames.get(path).get(field);
        if (source == null) throw new IllegalArgumentException("Unknown ONN field: " + path + "." + field);
        JSONObject def = attributes.get(path).get(source);
        if (!scalarSupported(def))
            throw new UnsupportedOperationException("ONN non-scalar, unspecified and derived attributes are unavailable in this adapter");
        return source;
    }
    String dataType(String path, String field) {
        String source=sourceField(path,field);
        return source==null ? "string" : attributes.get(path).get(source).getString("dataType");
    }
    /** Time values use ONN's accepted wire formats; zone-less dates/times are UTC. */
    Object temporalValue(String path, String field, Object value) {
        if (value==null || !"datetime".equals(dataType(path,field))) return value;
        if (value instanceof Instant) return value;
        try {
            if (value instanceof Number number) {
                long epoch=new BigDecimal(number.toString()).longValueExact();
                return epoch>1_000_000_000_000L || epoch< -1_000_000_000_000L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
            }
            if (value instanceof String text) {
                try { return Instant.parse(text); } catch (RuntimeException ignored) { }
                try { return OffsetDateTime.parse(text).toInstant(); } catch (RuntimeException ignored) { }
                try { return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant(); } catch (RuntimeException ignored) { }
                return LocalDateTime.parse(text,DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)).toInstant(ZoneOffset.UTC);
            }
        } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid ONN datetime value for "+path+"."+field,e); }
        throw new IllegalArgumentException("Unsupported ONN datetime value for "+path+"."+field);
    }
    JSONObject flatten(String path, JSONObject instance) {
        return flatten(path, instance, sourceNames.get(path).keySet());
    }
    JSONObject flatten(String path, JSONObject instance, Set<String> fields) {
        if (instance == null || !path.equals(instance.getString("classPath")) || instance.getString("id") == null)
            throw new IllegalStateException("ONN returned a missing or mismatched class/instance identity");
        JSONObject row = new JSONObject(); row.put("id", instance.getString("id"));
        JSONObject values = instance.getJSONObject("attributes");
        if (values == null) throw new IllegalStateException("ONN instance response is missing attributes");
        for (String product : fields) {
            if ("id".equals(product)) continue;
            String source = sourceNames.get(path).get(product);
            JSONObject def = attributes.get(path).get(source);
            if (scalarSupported(def)) row.put(product, values.get(source));
        }
        return row;
    }

    void validateMapping(Map<String, Object> mapping, String path, Set<String> fields) {
        Map<String, JSONObject> imported = importedAttributes(mapping, path);
        JSONObject expected = JSONObject.from(ontology).getJSONArray("classDef").stream().map(JSONObject::from)
            .filter(d -> path.equals(d.getString("className"))).findFirst().orElseThrow();
        for (Object raw : expected.getJSONArray("attrs")) {
            JSONObject attr = JSONObject.from(raw); String name=attr.getString("name");
            if (!fields.contains(name)) continue;
            JSONObject old=imported.get(name);
            if (old == null || Boolean.FALSE.equals(old.getBoolean("enable")) || !Objects.equals(old.getString("type"), attr.getString("type"))
                || !Objects.equals(old.getBoolean("primaryKey"), attr.getBoolean("primaryKey"))
                || !Objects.equals(old.getString("sourceName"),attr.getString("sourceName"))) {
                throw new IllegalStateException("ONN schema mapping changed or field is disabled; re-import schema: " + path + "." + name);
            }
        }
        JSONObject id=imported.get("id");
        if (id == null || !Boolean.TRUE.equals(id.getBoolean("primaryKey")) || Boolean.FALSE.equals(id.getBoolean("enable"))
            || !"@identity".equals(id.getString("sourceName")) || !"varchar".equals(id.getString("type")))
            throw new IllegalStateException("ONN requires the virtual id as primary key; re-import schema");
    }

    Set<String> disabledSampleFields(Map<String, Object> mapping, String path) {
        // Missing class/identity is an import error, never an empty successful sample.
        validateMapping(mapping, path, Set.of("id"));
        Set<String> disabled = new HashSet<>();
        importedAttributes(mapping, path).forEach((name, attr) -> {
            if (Boolean.FALSE.equals(attr.getBoolean("enable"))) disabled.add(name);
        });
        return disabled;
    }

    private static Map<String, JSONObject> importedAttributes(Map<String, Object> mapping, String path) {
        if (mapping == null) throw new IllegalStateException("Import the ONN schema into the Ontology Manager first");
        JSONObject rule = JSONObject.from(mapping);
        JSONArray defs = rule.getJSONArray("classDef");
        JSONObject cls = null;
        if (defs != null) for (int i=0;i<defs.size();i++) if (path.equals(defs.getJSONObject(i).getString("className"))) cls=defs.getJSONObject(i);
        if (cls == null) throw new IllegalStateException("Import the ONN schema into the Ontology Manager first; class is absent: " + path);
        Map<String, JSONObject> imported = new HashMap<>();
        JSONArray attrs = cls.getJSONArray("attrs");
        if (attrs == null) throw new IllegalStateException("Import the ONN schema into the Ontology Manager first; class attributes are absent: " + path);
        for (Object raw : attrs) {
            JSONObject attr = JSONObject.from(raw); imported.put(attr.getString("name"), attr);
        }
        return imported;
    }

    private static boolean scalarSupported(JSONObject def) {
        return def.get("derived") == null && Set.of("string", "int", "float", "boolean", "datetime")
            .contains(Objects.toString(def.getString("dataType"), ""));
    }

    private Map<String, JSONObject> resolve(String path, Set<String> visiting) {
        if (!visiting.add(path)) throw new IllegalArgumentException("ONN schema inheritance cycle: " + path);
        JSONObject cls=classes.get(path);
        if (cls==null) throw new IllegalArgumentException("Missing ONN parent class: " + path);
        Map<String, JSONObject> attrs=new TreeMap<>();
        String parent=cls.getString("parentPath");
        if (parent!=null && !parent.isBlank()) attrs.putAll(resolve(parent, visiting));
        JSONArray own=cls.getJSONArray("attributes");
        Set<String> ownNames = new HashSet<>();
        if (own!=null) for (int i=0;i<own.size();i++) {
            JSONObject a=own.getJSONObject(i); String name=a.getString("name");
            if (name==null || name.isBlank() || !ownNames.add(name)) throw new IllegalArgumentException("Invalid/duplicate ONN attribute: " + path);
            attrs.put(name,a);
        }
        visiting.remove(path); return attrs;
    }
    private static Map<String, Object> attr(String name, String type, String desc, boolean pk, boolean enable, String source) {
        Map<String, Object> attr=new LinkedHashMap<>();
        attr.put("name",name); attr.put("showName",name); attr.put("attrDesc",desc); attr.put("type",type);
        attr.put("bizzkey",false); attr.put("enable",enable); attr.put("permissionField",false); attr.put("primaryKey",pk);
        attr.put("sourceName",source); return attr;
    }
    static void validatePath(String path) {
        // Existing ONN deployments contain both slash paths and plain Unicode names.
        // HTTP query parameters are encoded; colons remain reserved for node references.
        if (path==null || path.isBlank() || path.equals("/") || path.contains(":") || path.contains("..") || path.contains("?")
            || path.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid ONN class/relation path: " + path);
    }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static Object canonical(Object value) {
        if (value instanceof Map<?,?> map) { Map<String,Object> out=new TreeMap<>(); map.forEach((k,v)->out.put(k.toString(),canonical(v))); return out; }
        if (value instanceof List<?> list) return list.stream().map(OnnCoreSchema::canonical).toList();
        return value;
    }
}

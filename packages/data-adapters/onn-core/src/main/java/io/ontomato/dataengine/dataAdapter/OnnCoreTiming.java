package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

// 请求级计时只保存固定阶段和数值，嵌套阶段由调用者明确包围范围。
final class OnnCoreTiming {
    enum Operation {
        PAGE("page"), SCHEMA("schema"), CLOSE("close"), CAPABILITIES("capabilities"), INSTANCE("instance"), OTHER("other");
        private final String label;
        Operation(String label) { this.label = label; }
    }
    private final Map<String, Double> phases = new LinkedHashMap<>();
    private final Map<String, Long> counters = new LinkedHashMap<>();
    void add(String phase, long started) { phases.merge(phase, (System.nanoTime() - started) / 1_000_000d, Double::sum); }
    void add(Operation operation, String phase, long started) {
        double elapsed = (System.nanoTime() - started) / 1_000_000d;
        phases.merge(phase, elapsed, Double::sum);
        phases.merge(operation.label + "." + phase, elapsed, Double::sum);
    }
    void count(String name, long value) { counters.merge(name, value, Long::sum); }
    void count(Operation operation, String name, long value) { count(operation.label + "." + name, value); }
    JSONObject phases() { return new JSONObject(phases); }
    JSONObject counters() { return new JSONObject(counters); }
}

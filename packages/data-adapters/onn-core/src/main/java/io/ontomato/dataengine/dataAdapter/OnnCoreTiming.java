package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

// 请求级计时只保存固定阶段和数值，嵌套阶段由调用者明确包围范围。
final class OnnCoreTiming {
    private final Map<String, Double> phases = new LinkedHashMap<>();
    private final Map<String, Long> counters = new LinkedHashMap<>();
    void add(String phase, long started) { phases.merge(phase, (System.nanoTime() - started) / 1_000_000d, Double::sum); }
    void count(String name, long value) { counters.merge(name, value, Long::sum); }
    JSONObject phases() { return new JSONObject(phases); }
    JSONObject counters() { return new JSONObject(counters); }
}

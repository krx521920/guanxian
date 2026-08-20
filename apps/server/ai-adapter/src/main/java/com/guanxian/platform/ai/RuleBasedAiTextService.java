package com.guanxian.platform.ai;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RuleBasedAiTextService implements AiTextService {
    private static final Map<String, String> DICTIONARY = new LinkedHashMap<>();

    static {
        DICTIONARY.put("燃气", "燃气管网");
        DICTIONARY.put("供水", "供水管网");
        DICTIONARY.put("阀门", "阀门设备");
        DICTIONARY.put("泄漏", "泄漏监测");
        DICTIONARY.put("探测", "探测测绘");
        DICTIONARY.put("数字孪生", "数字孪生");
        DICTIONARY.put("非开挖", "非开挖修复");
    }

    @Override
    public List<String> extractTags(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return DICTIONARY.entrySet().stream()
                .filter(entry -> text.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .distinct()
                .toList();
    }
}

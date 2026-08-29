package com.guanxian.platform.ai.rag;

import java.util.List;

public interface EmbeddingProvider {
    String providerName();
    String modelName();
    int dimensions();
    boolean enabled();
    List<double[]> embed(List<String> inputs);

    static EmbeddingProvider disabled() {
        return new EmbeddingProvider() {
            @Override public String providerName() { return "disabled"; }
            @Override public String modelName() { return "disabled"; }
            @Override public int dimensions() { return 0; }
            @Override public boolean enabled() { return false; }
            @Override public List<double[]> embed(List<String> inputs) {
                throw new IllegalStateException("embedding provider is disabled");
            }
        };
    }
}

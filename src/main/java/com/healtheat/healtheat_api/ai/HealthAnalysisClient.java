package com.healtheat.healtheat_api.ai;

import com.healtheat.healtheat_api.health.HealthDtos.HealthFactorAnalysisResponse;

public interface HealthAnalysisClient {
    HealthFactorAnalysisResponse analyze(String text);
}

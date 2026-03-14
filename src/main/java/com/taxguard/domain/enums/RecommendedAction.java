package com.taxguard.domain.enums;

public enum RecommendedAction {
    SAFE_TO_DEPLOY,    // Divergence < 1% — proceed through normal pipeline
    MANUAL_REVIEW,     // Divergence 1-5% — finance team must review before deploy
    BLOCK_DEPLOYMENT   // Divergence > 5% — hard block until root cause is understood
}

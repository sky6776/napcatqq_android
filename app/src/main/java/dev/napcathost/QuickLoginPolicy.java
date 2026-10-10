package dev.napcathost;

final class QuickLoginPolicy {
    static final int MAX_OBSERVATIONS = 11;
    static final long OBSERVATION_INTERVAL_SECONDS = 2L;

    enum Decision {
        WAIT,
        LOGGED_IN,
        USE_PASSWORD
    }

    private QuickLoginPolicy() {
    }

    static Decision evaluate(boolean loggedIn, String loginError, int observation) {
        if (loggedIn) {
            return Decision.LOGGED_IN;
        }
        if (loginError != null && !loginError.trim().isEmpty()) {
            return Decision.USE_PASSWORD;
        }
        return observation >= MAX_OBSERVATIONS
                ? Decision.USE_PASSWORD
                : Decision.WAIT;
    }
}

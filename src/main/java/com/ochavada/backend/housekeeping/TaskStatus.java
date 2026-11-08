package com.ochavada.backend.housekeeping;

public enum TaskStatus {
    PENDING, IN_PROGRESS, DONE, VERIFIED, CANCELLED;

    public boolean isOpen() {
        return this == PENDING || this == IN_PROGRESS;
    }
}

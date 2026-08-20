package common.dto.exam;

import java.io.Serializable;

/**
 * "Show me this execution, live" (Common tier, E11.2 — F7.2).
 *
 * <p>Asking also <b>subscribes</b>: the teacher who sends this is registered as a watcher
 * of the execution and receives {@code PUSH_MONITOR_UPDATED} on every change until she
 * disconnects or signs out. That is the same "whoever asked is watching" mechanism the
 * edit locks use, and it needs no second verb for the same reason — a screen that cares
 * about an execution is exactly the screen that asked for it (NFR-18: nothing here is
 * refreshed by hand).
 *
 * @param executionId the execution to watch
 */
public record MonitorRequest(long executionId) implements Serializable {

    private static final long serialVersionUID = 1L;
}

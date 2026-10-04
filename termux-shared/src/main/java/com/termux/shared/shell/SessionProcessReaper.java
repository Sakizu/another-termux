package com.termux.shared.shell;

import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Best-effort reaper for processes orphaned by a finished terminal session.
 *
 * <p>When a session's shell exits, children it backgrounded or disowned can survive as
 * orphans, silently consuming battery and CPU (upstream termux-app#935). They cannot be
 * found through the parent-pid chain: the shell is reaped by {@code waitpid()} in
 * {@code TerminalSession}'s waiter thread before the session-finished callback runs, so by
 * the time anyone looks, the orphans have been reparented to init and the ancestry is
 * gone. Instead this reaper matches on the <b>session id</b>: the shell is started as a
 * session leader ({@code setsid()} in the {@code termux.c} child after fork, so its sid
 * equals its pid), the sid is inherited across {@code fork()}, and reparenting does not
 * change it. The relevant {@code /proc/[pid]/stat} fields are pid(1), ppid(4), pgrp(5)
 * and session(6).</p>
 *
 * <p>Kill order is polite but escape-proof: every match is sent SIGTERM first — targets
 * are still running, so handlers can run and state can be flushed — then, after a short
 * grace period, SIGSTOP plus rescans until no new processes appear (a stopped process
 * cannot fork, so children forked during the grace period are caught), and finally
 * SIGKILL, built from a fresh scan so anything still alive at KILL time is included.
 * Each signal is preceded by a fresh {@code /proc} re-read so a pid recycled between
 * the scan and the signal is never touched. The reaper additionally aborts entirely if
 * {@code /proc/[shellPid]} itself has been recycled by a new session leader. (A fork
 * landing in the microseconds of the KILL wave itself remains inherently unclosable.)</p>
 *
 * <p><b>Known limitation:</b> processes that called {@code setsid()} themselves thereby
 * join a new session and are <i>not</i> caught — true daemons intentionally detach from
 * the session, and there is no reliable way to attribute them to it afterwards.</p>
 *
 * <p>Everything is best-effort and this class never throws: individual failures are
 * logged and skipped. Same-UID signaling is enforced by the kernel anyway, so the worst
 * case of a bug here is a failed signal, not a privilege escalation.</p>
 */
public final class SessionProcessReaper {

    private static final String LOG_TAG = "SessionProcessReaper";

    // Signal numbers as literals: android.os.Process.sendSignal() takes the raw number,
    // and the SIGNAL_* constants are not guaranteed public API.
    private static final int SIGTERM = 15; // polite shutdown request; can be caught
    private static final int SIGSTOP = 19; // cannot be caught or ignored
    private static final int SIGKILL = 9;  // cannot be caught or ignored
    private static final int SIGCONT = 18; // resume a stopped process

    /** Grace period after SIGTERM before the freeze/kill waves, in milliseconds. */
    private static final long TERM_GRACE_MS = 500;

    private SessionProcessReaper() {
    }

    /** Identity fields parsed from a {@code /proc/[pid]/stat} line. */
    public static final class ProcStat {
        public final int pid;
        public final int ppid;
        public final int pgrp;
        public final int session;
        /** Process state as reported in {@code /proc/[pid]/stat} (e.g. 'R', 'S', 'T', 'Z'). */
        public final char state;

        public ProcStat(int pid, int ppid, int pgrp, int session, char state) {
            this.pid = pid;
            this.ppid = ppid;
            this.pgrp = pgrp;
            this.session = session;
            this.state = state;
        }
    }

    /**
     * Parse a {@code /proc/[pid]/stat} line into its identity fields.
     *
     * <p>The comm field (2) may itself contain spaces and parentheses, so the fields
     * after it are located from the <i>last</i> {@code ')'} in the line, not the first.
     * Returns {@code null} when the line is malformed. Pure function, safe to unit test
     * on the JVM.</p>
     */
    @Nullable
    public static ProcStat parseStatLine(@Nullable String line) {
        if (line == null) return null;
        int lparen = line.indexOf('(');
        int rparen = line.lastIndexOf(')');
        if (lparen < 0 || rparen < 0 || rparen <= lparen) return null;
        try {
            int pid = Integer.parseInt(line.substring(0, lparen).trim());
            // After the closing paren: "state ppid pgrp session tty_nr ...".
            String[] fields = line.substring(rparen + 1).trim().split("\\s+");
            if (fields.length < 4) return null;
            char state = fields[0].charAt(0);
            int ppid = Integer.parseInt(fields[1]);
            int pgrp = Integer.parseInt(fields[2]);
            int session = Integer.parseInt(fields[3]);
            return new ProcStat(pid, ppid, pgrp, session, state);
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return null;
        }
    }

    /**
     * Whether this process belongs to the finished session's tree and is safe to signal:
     * same session id, but never the session-leader pid itself (the kernel may have
     * recycled it for an unrelated process), never our own pid, never pid &lt;= 1.
     * Pure function, safe to unit test on the JVM.
     */
    public static boolean belongsToSession(@Nullable ProcStat stat, int sessionLeaderPid, int selfPid) {
        if (stat == null) return false;
        if (stat.session != sessionLeaderPid) return false;
        if (stat.pid == sessionLeaderPid) return false;
        if (stat.pid == selfPid) return false;
        return stat.pid > 1;
    }

    /**
     * Read and parse {@code /proc/[pid]/stat}. Returns {@code null} on any failure
     * (process exited, entry unreadable, malformed content).
     */
    @Nullable
    public static ProcStat readProcStat(int pid) {
        if (pid <= 0) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/" + pid + "/stat"))) {
            return parseStatLine(reader.readLine());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Best-effort scan of {@code /proc} for processes belonging to the given session.
     * Never throws; returns an empty list when {@code /proc} cannot be listed.
     */
    @NonNull
    public static List<Integer> findSessionProcesses(int sessionLeaderPid) {
        List<Integer> result = new ArrayList<>();
        int selfPid = Process.myPid();
        String[] entries = new File("/proc").list();
        if (entries == null) return result;
        for (String entry : entries) {
            final int pid;
            try {
                pid = Integer.parseInt(entry);
            } catch (NumberFormatException e) {
                continue; // not a pid entry (e.g. "self", "net", "sys")
            }
            if (belongsToSession(readProcStat(pid), sessionLeaderPid, selfPid)) {
                result.add(pid);
            }
        }
        return result;
    }

    /**
     * Guard against pid recycling: if {@code /proc/[sessionLeaderPid]} exists and is
     * itself a session leader (sid == pid), the kernel has recycled the pid for a new,
     * unrelated session — abort rather than risk killing the wrong tree. (The old shell
     * was already reaped by {@code waitpid()}, so any live entry here is a new process.)
     */
    private static boolean sessionLeaderPidRecycled(int sessionLeaderPid) {
        ProcStat stat = readProcStat(sessionLeaderPid);
        return stat != null && stat.session == sessionLeaderPid;
    }

    /**
     * Send a signal to {@code pid}, but only if a fresh {@code /proc} re-read still shows
     * it belonging to the session. This closes the race where the pid was recycled
     * between the initial scan and the signal. Never throws.
     */
    private static void signalIfStillOurs(int pid, int sessionLeaderPid, int selfPid, int signal) {
        try {
            if (!belongsToSession(readProcStat(pid), sessionLeaderPid, selfPid)) return;
            Process.sendSignal(pid, signal);
        } catch (Exception e) {
            // Best effort: the process may have exited, or the kernel refused.
            Logger.logWarn(LOG_TAG, "Failed to send signal " + signal + " to pid " + pid + ": " + e.getMessage());
        }
    }

    /**
     * Send SIGSTOP to {@code pid} (after the usual fresh re-read), recording its prior
     * state so an abort can resume it. The first observed state wins: later iterations
     * of the freeze loop re-read the pid after our own STOP landed (state 'T'), which
     * must not overwrite the genuinely pre-STOP state. Never throws.
     */
    private static void stopAndTrack(int pid, int sessionLeaderPid, int selfPid,
                                     Map<Integer, Character> stoppedByUs) {
        try {
            ProcStat stat = readProcStat(pid);
            if (!belongsToSession(stat, sessionLeaderPid, selfPid)) return;
            Process.sendSignal(pid, SIGSTOP);
            stoppedByUs.putIfAbsent(pid, stat.state);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to send signal " + SIGSTOP + " to pid " + pid + ": " + e.getMessage());
        }
    }

    /**
     * Whether a process we STOPped should be resumed (SIGCONT) if the reap aborts
     * mid-run. Processes that were already stopped before we arrived (e.g. job
     * control) are left alone; only our own STOPs are undone. Pure function.
     */
    static boolean shouldResumeOnAbort(char stateBeforeOurStop) {
        return stateBeforeOurStop != 'T' && stateBeforeOurStop != 't';
    }

    /**
     * Kill processes orphaned by the finished session whose shell had the given pid
     * (which was its session id — the shell is started as a session leader).
     * Never throws.
     */
    public static void reap(int sessionLeaderPid) {
        try {
            reapInternal(sessionLeaderPid);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Session process reap failed: " + e.getMessage());
        }
    }

    private static void reapInternal(int sessionLeaderPid) {
        if (sessionLeaderPid <= 1) return;
        int selfPid = Process.myPid();
        if (sessionLeaderPid == selfPid) return; // sanity: never reap our own session

        if (sessionLeaderPidRecycled(sessionLeaderPid)) {
            Logger.logWarn(LOG_TAG, "Not reaping session " + sessionLeaderPid
                + ": pid has been recycled by a new session leader");
            return;
        }

        List<Integer> targets = findSessionProcesses(sessionLeaderPid);
        if (targets.isEmpty()) return;
        if (sessionLeaderPidRecycled(sessionLeaderPid)) {
            // Nothing has been signaled yet, so there is nothing to clean up.
            Logger.logWarn(LOG_TAG, "Aborting reap of session " + sessionLeaderPid
                + ": pid recycled by a new session leader");
            return;
        }
        Logger.logInfo(LOG_TAG, "Reaping " + targets.size()
            + " orphaned process(es) of finished session " + sessionLeaderPid);

        // Polite wave first: targets are still running, so SIGTERM handlers can run
        // and processes can flush state and exit cleanly on their own.
        for (int pid : targets) signalIfStillOurs(pid, sessionLeaderPid, selfPid, SIGTERM);

        try {
            Thread.sleep(TERM_GRACE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (sessionLeaderPidRecycled(sessionLeaderPid)) {
            // Nothing has been stopped yet, so there is nothing to clean up.
            Logger.logWarn(LOG_TAG, "Aborting reap of session " + sessionLeaderPid
                + ": pid recycled by a new session leader mid-run");
            return;
        }

        // Freeze loop: STOP everything known, then rescan for children forked during
        // the grace period or the STOP wave itself; repeat until no newcomers. A
        // stopped process cannot fork, so this converges. Bounded as a failsafe.
        // What we stop (and their prior state) is remembered so an abort below can
        // resume them instead of leaving them stopped.
        Map<Integer, Character> stoppedByUs = new HashMap<>();
        boolean converged = false;
        for (int i = 0; i < 5; i++) {
            for (int pid : targets) stopAndTrack(pid, sessionLeaderPid, selfPid, stoppedByUs);
            boolean added = false;
            for (int pid : findSessionProcesses(sessionLeaderPid)) {
                if (!targets.contains(pid)) {
                    targets.add(pid);
                    added = true;
                }
            }
            if (!added) {
                converged = true;
                break;
            }
        }
        if (!converged) {
            Logger.logWarn(LOG_TAG, "Freeze loop did not converge for session " + sessionLeaderPid
                + "; proceeding to kill anyway");
        }

        if (sessionLeaderPidRecycled(sessionLeaderPid)) {
            Logger.logWarn(LOG_TAG, "Aborting reap of session " + sessionLeaderPid
                + ": pid recycled by a new session leader mid-run; resuming stopped processes");
            for (Map.Entry<Integer, Character> entry : stoppedByUs.entrySet()) {
                // Do not resume processes that were already stopped before we arrived
                // (e.g. job control); only undo our own STOPs.
                if (shouldResumeOnAbort(entry.getValue())) {
                    signalIfStillOurs(entry.getKey(), sessionLeaderPid, selfPid, SIGCONT);
                }
            }
            return;
        }

        // Kill wave, built from a fresh scan: SIGSTOP delivery is asynchronous, so a
        // target could have forked between the final freeze-loop rescan and now; the
        // fresh scan catches anything alive at KILL time. The per-pid re-read inside
        // signalIfStillOurs keeps this safe against pid recycling. (A fork landing in
        // the microseconds of the KILL wave itself remains inherently unclosable.)
        for (int pid : findSessionProcesses(sessionLeaderPid)) {
            signalIfStillOurs(pid, sessionLeaderPid, selfPid, SIGKILL);
        }
    }
}

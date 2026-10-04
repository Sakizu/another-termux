package com.termux.shared.shell;

import junit.framework.TestCase;

/**
 * Unit tests for the pure functions of {@link SessionProcessReaper}
 * ({@link SessionProcessReaper#parseStatLine} and {@link SessionProcessReaper#belongsToSession})
 * using synthetic {@code /proc/[pid]/stat} lines. The impure parts (proc scanning,
 * signaling) need a device and are not covered here.
 */
public class SessionProcessReaperTest extends TestCase {

    private static final int LEADER_PID = 1234;
    private static final int SELF_PID = 99999;

    public void testParseStatLineNormal() {
        SessionProcessReaper.ProcStat stat =
            SessionProcessReaper.parseStatLine("1234 (bash) S 1230 1234 1234 0 -1 4194304 1 0 0 0 0 0 0 0 20 0 1 0 123456 4294967296 1 1 0 0 0 0 0 0 0 0 0 0 0 17 0 0 0 0 0 0");
        assertNotNull(stat);
        assertEquals(1234, stat.pid);
        assertEquals(1230, stat.ppid);
        assertEquals(1234, stat.pgrp);
        assertEquals(1234, stat.session);
        assertEquals('S', stat.state);
    }

    public void testParseStatLineCommWithSpaces() {
        SessionProcessReaper.ProcStat stat =
            SessionProcessReaper.parseStatLine("1235 (my background app) S 1234 1234 1234 0 -1 4194304 1 0 0 0");
        assertNotNull(stat);
        assertEquals(1235, stat.pid);
        assertEquals(1234, stat.ppid);
        assertEquals(1234, stat.session);
        assertEquals('S', stat.state);
    }

    public void testParseStatLineCommWithParens() {
        // comm may contain parentheses; fields after it are located from the LAST ')'.
        SessionProcessReaper.ProcStat stat =
            SessionProcessReaper.parseStatLine("1236 (weird)name) S 1234 1234 1234 0 -1 4194304 1 0 0 0");
        assertNotNull(stat);
        assertEquals(1236, stat.pid);
        assertEquals(1234, stat.ppid);
        assertEquals(1234, stat.session);
        assertEquals('S', stat.state);
    }

    public void testParseStatLineCommEndingInParen() {
        // The exact case lastIndexOf(')') exists for: comm itself ends with ')'.
        SessionProcessReaper.ProcStat stat =
            SessionProcessReaper.parseStatLine("1237 (bash)) T 1234 1234 1234 0 -1 4194304 1 0 0 0");
        assertNotNull(stat);
        assertEquals(1237, stat.pid);
        assertEquals(1234, stat.ppid);
        assertEquals(1234, stat.session);
        assertEquals('T', stat.state);
    }

    public void testParseStatLineMalformed() {
        assertNull(SessionProcessReaper.parseStatLine(null));
        assertNull(SessionProcessReaper.parseStatLine(""));
        assertNull(SessionProcessReaper.parseStatLine("no parens here at all"));
        assertNull(SessionProcessReaper.parseStatLine("1234 (bash)")); // truncated: no fields after comm
        assertNull(SessionProcessReaper.parseStatLine("1234 (bash) S 1230")); // truncated: missing pgrp/session
        assertNull(SessionProcessReaper.parseStatLine("nan (bash) S 1230 1234 1234 0")); // non-numeric pid
        assertNull(SessionProcessReaper.parseStatLine("1234 (bash) S abc 1234 1234 0")); // non-numeric ppid
    }

    public void testBelongsToSessionOrphan() {
        SessionProcessReaper.ProcStat orphan =
            new SessionProcessReaper.ProcStat(5678, 1, 5678, LEADER_PID, 'S');
        assertTrue(SessionProcessReaper.belongsToSession(orphan, LEADER_PID, SELF_PID));
    }

    public void testBelongsToSessionExclusions() {
        // The session leader pid itself: may have been recycled by the kernel.
        assertFalse(SessionProcessReaper.belongsToSession(
            new SessionProcessReaper.ProcStat(LEADER_PID, 1, LEADER_PID, LEADER_PID, 'S'), LEADER_PID, SELF_PID));
        // Our own process: never signal ourselves.
        assertFalse(SessionProcessReaper.belongsToSession(
            new SessionProcessReaper.ProcStat(SELF_PID, 1, SELF_PID, LEADER_PID, 'R'), LEADER_PID, SELF_PID));
        // A process in a different session.
        assertFalse(SessionProcessReaper.belongsToSession(
            new SessionProcessReaper.ProcStat(5678, 1, 5678, 7777, 'S'), LEADER_PID, SELF_PID));
        // pid <= 1 is never signaled.
        assertFalse(SessionProcessReaper.belongsToSession(
            new SessionProcessReaper.ProcStat(1, 0, 1, LEADER_PID, 'S'), LEADER_PID, SELF_PID));
        // Null stat.
        assertFalse(SessionProcessReaper.belongsToSession(null, LEADER_PID, SELF_PID));
    }

    public void testShouldResumeOnAbort() {
        // Only our own STOPs are undone on abort; pre-existing stops are left alone.
        assertTrue(SessionProcessReaper.shouldResumeOnAbort('S'));
        assertTrue(SessionProcessReaper.shouldResumeOnAbort('R'));
        assertFalse(SessionProcessReaper.shouldResumeOnAbort('T'));
        assertFalse(SessionProcessReaper.shouldResumeOnAbort('t'));
    }
}

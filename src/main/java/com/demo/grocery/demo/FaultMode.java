package com.demo.grocery.demo;

public enum FaultMode {
    /** Service operates normally. */
    NORMAL,
    /** Service is completely unreachable — throws immediately. */
    DOWN,
    /** Service responds after a configurable delay (simulates latency / near-timeout). */
    SLOW,
    /** Service fails every other request — simulates intermittent instability. */
    FLAKY
}

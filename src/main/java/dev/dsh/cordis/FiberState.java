package dev.dsh.cordis;

/** Lifecycle state for one plugin fiber. */
public enum FiberState { PENDING, LOADING, ACTIVE, FAILED, DISPOSED, UNLOADING }

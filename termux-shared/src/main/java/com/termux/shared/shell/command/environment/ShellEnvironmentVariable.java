package com.termux.shared.shell.command.environment;

public class ShellEnvironmentVariable implements Comparable<ShellEnvironmentVariable> {

    /** The name for environment variable */
    public String name;

    /** The value for environment variable */
    public String value;

    /** If environment variable {@link #value} is already escaped. */
    public boolean escaped;

    public ShellEnvironmentVariable(String name, String value) {
        this(name, value, false);
    }

    public ShellEnvironmentVariable(String name, String value, boolean escaped) {
        this.name = name;
        this.value = value;
        this.escaped = escaped;
    }

    @Override
    public int compareTo(ShellEnvironmentVariable other) {
        // Use nulls-first ordering to avoid NPE when names are null
        if (this.name == null && other.name == null) return 0;
        if (this.name == null) return -1;
        if (other.name == null) return 1;
        return this.name.compareTo(other.name);
    }
}

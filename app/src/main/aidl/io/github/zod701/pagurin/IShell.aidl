package io.github.zod701.pagurin;

// ShellService's interface: Pagurin's scripts, run by the shell user Shizuku starts it as (ShizukuShell)
interface IShell {
    void destroy() = 16777114;   // Shizuku's code for ending a user service
    String run(String script) = 1;   // its output and errors, as one text
}

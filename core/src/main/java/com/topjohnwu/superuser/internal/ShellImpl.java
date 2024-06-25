/*
 * Copyright 2024 John "topjohnwu" Wu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.topjohnwu.superuser.internal;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ShellUtils;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class ShellImpl extends Shell {
    private volatile int status;

    private final Process proc;
    private final NoCloseOutputStream STDIN;
    private final NoCloseInputStream STDOUT;
    private final NoCloseInputStream STDERR;
    private final ArrayDeque<Task> tasks = new ArrayDeque<>();
    private boolean runningTasks = false;

    private static class NoCloseInputStream extends FilterInputStream {

        NoCloseInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {}

        void close0() throws IOException {
            in.close();
        }
    }

    private static class NoCloseOutputStream extends FilterOutputStream {

        NoCloseOutputStream(@NonNull OutputStream out) {
            super((out instanceof BufferedOutputStream) ? out : new BufferedOutputStream(out));
        }

        @Override
        public void write(@NonNull byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            out.flush();
        }

        void close0() throws IOException {
            super.close();
        }
    }

    ShellImpl(BuilderImpl builder, Process process) throws IOException {
        status = UNKNOWN;
        proc = process;
        STDIN = new NoCloseOutputStream(process.getOutputStream());
        STDOUT = new NoCloseInputStream(process.getInputStream());
        STDERR = new NoCloseInputStream(process.getErrorStream());

        // Shell checks might get stuck indefinitely
        FutureTask<Integer> check = new FutureTask<>(this::shellCheck);
        EXECUTOR.execute(check);
        try {
            try {
                status = check.get(builder.timeout, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                } else {
                    throw new IOException("Unknown ExecutionException", cause);
                }
            } catch (TimeoutException e) {
                throw new IOException("Shell check timeout", e);
            } catch (InterruptedException e) {
                throw new IOException("Shell check interrupted", e);
            }
        } catch (IOException e) {
            release();
            throw e;
        }
    }

    private Integer shellCheck() throws IOException {
        try {
            proc.exitValue();
            throw new IOException("Created process has terminated");
        } catch (IllegalThreadStateException ignored) {
            // Process is alive
        }

        // Clean up potential garbage from InputStreams
        ShellUtils.cleanInputStream(STDOUT);
        ShellUtils.cleanInputStream(STDERR);

        int status = NON_ROOT_SHELL;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(STDOUT))) {

            STDIN.write(("echo SHELL_TEST\n").getBytes(UTF_8));
            STDIN.flush();
            String s = br.readLine();
            if (TextUtils.isEmpty(s) || !s.contains("SHELL_TEST"))
                throw new IOException("Created process is not a shell");

            STDIN.write(("id\n").getBytes(UTF_8));
            STDIN.flush();
            s = br.readLine();
            if (!TextUtils.isEmpty(s) && s.contains("uid=0")) {
                status = ROOT_SHELL;
                Utils.setConfirmedRootState(true);
                // noinspection ConstantConditions
                String cwd = ShellUtils.escapedString(System.getProperty("user.dir"));
                STDIN.write(("cd " + cwd + "\n").getBytes(UTF_8));
                STDIN.flush();
            }
        }
        return status;
    }

    private void release() {
        status = UNKNOWN;
        try { STDIN.close0(); } catch (IOException ignored) {}
        try { STDERR.close0(); } catch (IOException ignored) {}
        try { STDOUT.close0(); } catch (IOException ignored) {}
        proc.destroy();
    }

    @Override
    public boolean waitAndClose(long timeout, @NonNull TimeUnit unit) throws InterruptedException {
        if (status < 0)
            return true;

        synchronized (tasks) {
            if (runningTasks) {
                tasks.clear();
                tasks.wait(unit.toMillis(timeout));
            }
            if (!runningTasks) {
                release();
                return true;
            }
        }

        status = UNKNOWN;
        return false;
    }

    @Override
    public void close() {
        if (status < 0)
            return;
        release();
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public boolean isAlive() {
        // If status is unknown, it is not alive
        if (status < 0)
            return false;

        try {
            proc.exitValue();
            // Process is dead, shell is not alive
            return false;
        } catch (IllegalThreadStateException e) {
            // Process is still running
            return true;
        }
    }

    private synchronized void exec0(@NonNull Task task) throws IOException {
        if (status < 0) {
            task.shellDied();
            return;
        }

        ShellUtils.cleanInputStream(STDOUT);
        ShellUtils.cleanInputStream(STDERR);
        try {
            STDIN.write('\n');
            STDIN.flush();
        } catch (IOException e) {
            release();
            task.shellDied();
            return;
        }

        task.run(STDIN, STDOUT, STDERR);
    }

    private void processTasks() {
        for (;;) {
            Task task;
            synchronized (tasks) {
                if ((task = tasks.poll()) == null) {
                    runningTasks = false;
                    tasks.notifyAll();
                    return;
                }
            }
            try {
                exec0(task);
            } catch (IOException ignored) {}
        }
    }

    @Override
    public void submitTask(@NonNull Task task) {
        synchronized (tasks) {
            tasks.offer(task);
            if (!runningTasks) {
                runningTasks = true;
                EXECUTOR.execute(this::processTasks);
            }
        }
    }

    @Override
    public void execTask(@NonNull Task task) throws IOException {
        synchronized (tasks) {
            while (runningTasks) {
                // Wait until all existing tasks are done
                try {
                    tasks.wait();
                } catch (InterruptedException ignored) {}
            }
        }
        exec0(task);
    }

    @NonNull
    @Override
    public Job newJob() {
        return new ShellJob(this);
    }
}

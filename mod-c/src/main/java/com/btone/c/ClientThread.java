package com.btone.c;

import net.minecraft.client.MinecraftClient;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Trampolines work onto the Minecraft client thread.
 *
 * Re-entry guard: if the caller is already on the client thread, run inline.
 * Calling {@code mc.submit(...).get()} from inside the client thread
 * deadlocks (the queued task waits for the same thread to drain it).
 */
public final class ClientThread {
    private ClientThread() {}

    public static <T> T call(long timeoutMs, Supplier<T> block) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.isOnThread()) return block.get();
        try {
            return mc.submit(block::get).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause != null ? cause : ee);
        } catch (TimeoutException te) {
            throw new RuntimeException(te);
        }
    }

    public static void run(Runnable block) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.isOnThread()) block.run();
        else mc.execute(block);
    }
}

package dev.horizon.input.core.sdl.state_caching;

import dev.horizon.input.core.threading.MainThreadDispatcher;
import dev.horizon.input.core.util.VolatileObjectHolder;

import java.util.function.LongSupplier;

public class BasicSdlVoid2LongCache {
    protected final LongSupplier supplier;
    private VolatileObjectHolder<Long> cached;
    public BasicSdlVoid2LongCache(LongSupplier supplier) {
        this.supplier = supplier;
    }

    public long get() {
        if (cached == null) {
            return blockingGet();
        }
        scheduleUpdate();
        return cached.getValue();
    }

    protected long blockingGet() {
        this.cached = new VolatileObjectHolder<>(MainThreadDispatcher.query(supplier::getAsLong));
        return this.cached.getValue();
    }

    protected void scheduleUpdate() {
        MainThreadDispatcher.run(() -> this.cached.setValue(supplier.getAsLong()));
    }
}

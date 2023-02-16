package com.dream.video.cache;

import java.io.ByteArrayInputStream;
import java.util.Arrays;

import static com.dream.video.cache.Preconditions.checkArgument;
import static com.dream.video.cache.Preconditions.checkNotNull;

/**
 * Simple memory based {@link Cache} implementation.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public class ByteArrayCache implements Cache {

    private volatile byte[] data;
    private volatile boolean completed;

    public ByteArrayCache() {
        this(new byte[0]);
    }

    public ByteArrayCache(byte[] data) {
        this.data = checkNotNull(data);
    }

    @Override
    public int read(byte[] buffer, long offset, int length) throws ProxyCacheException {
        if (offset >= data.length) {
            return -1;
        }
        if (offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Too long offset for memory cache " + offset);
        }
        return new ByteArrayInputStream(data).read(buffer, (int) offset, length);
    }

    @Override
    public long available() throws ProxyCacheException {
        return data.length;
    }

    @Override
    public void append(byte[] newData, int length) throws ProxyCacheException {
        checkNotNull(data);
        checkArgument(length >= 0 && length <= newData.length);

        byte[] appendedData = Arrays.copyOf(data, data.length + length);
        System.arraycopy(newData, 0, appendedData, data.length, length);
        data = appendedData;
    }

    @Override
    public void close() throws ProxyCacheException {
    }

    @Override
    public void complete() {
        completed = true;
    }

    @Override
    public boolean isCompleted() {
        return completed;
    }
}

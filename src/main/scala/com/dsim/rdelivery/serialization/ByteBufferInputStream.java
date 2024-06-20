package com.dsim.rdelivery.serialization;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Simple {@link InputStream} implementation that exposes currently
 * available content of a {@link ByteBuffer}.
 *
 *
 * From com.fasterxml.jackson.databind.util.ByteBufferBackedInputStream
 */
public class ByteBufferInputStream extends InputStream {

    private final ByteBuffer buf;

    public ByteBufferInputStream(ByteBuffer buf) {
        this.buf = buf;
    }

    @Override
    public int available() { return buf.remaining(); }

    @Override
    public int read() throws IOException {
        return buf.hasRemaining() ? buf.get() : -1;
    }

    @Override
    public int read(byte[] bytes, int off, int len) throws IOException {
        if (!buf.hasRemaining()) return -1;

        len = Math.min(len, buf.remaining());
        buf.get(bytes, off, len);
        return len;
    }
}
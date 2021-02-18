package com.dsim.rdelivery.serialization;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.io.OutputStream;

//https://gist.github.com/hoijui/7fe8a6d31b20ae7af945
//https://netflix.github.io/astyanax/javadoc/com/netflix/astyanax/serializers/ByteBufferOutputStream.html

/** An OutputStream whose target is a {@link ByteBuffer}. If bytes would be written that would overflow the buffer,
 * {@link #flush()} is called. Subclasses can override flush to empty the buffer.
 * @author Nathan Sweet */
public class ByteBufferOutputStream extends OutputStream {

	private ByteBuffer byteBuffer;

	public ByteBufferOutputStream (ByteBuffer byteBuffer) {
		this.byteBuffer = byteBuffer;
	}

	@Override
	public void write(int b) throws IOException {
		if (!byteBuffer.hasRemaining()) flush();
		byteBuffer.put((byte)b);
	}

	@Override
	public void write(byte[] bytes, int offset, int length) throws IOException {
		if (byteBuffer.remaining() < length) flush();
		byteBuffer.put(bytes, offset, length);
	}
}
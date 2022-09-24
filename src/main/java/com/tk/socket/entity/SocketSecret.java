package com.tk.socket.entity;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;

@Slf4j
public class SocketSecret {

    private final Encrypt encode;

    public Encrypt getEncode() {
        return encode;
    }

    private final Decrypt decode;

    public Decrypt getDecode() {
        return decode;
    }

    public SocketSecret(Encrypt encode, Decrypt decode) {
        this.encode = encode;
        this.decode = decode;
    }

    public ByteBuf encode(ByteBuf byteBuf) {
        ByteBuffer byteBuffer = toNioBuffer(byteBuf);
        ByteBuffer byteBuffer1 = encode.encode(byteBuffer);
        log.debug("encode:{}", byteBuffer1.array());
        return Unpooled.wrappedBuffer(byteBuffer1);
    }

    public ByteBuf decode(ByteBuf byteBuf) {
        ByteBuffer byteBuffer = toNioBuffer(byteBuf);
        ByteBuffer byteBuffer1 = decode.decode(byteBuffer);
        log.debug("decode:{}", byteBuffer1.array());
        return Unpooled.wrappedBuffer(byteBuffer1);
    }

    @FunctionalInterface
    public interface Encrypt {
        ByteBuffer encode(ByteBuffer data);
    }

    @FunctionalInterface
    public interface Decrypt {
        ByteBuffer decode(ByteBuffer data);
    }

    public static ByteBuffer toNioBuffer(ByteBuf byteBuf) {
        if (byteBuf.isDirect()) {
            return byteBuf.nioBuffer();
        }
        byte[] bytes = new byte[byteBuf.writerIndex()];
        byteBuf.getBytes(0, bytes);
        log.debug("bytes:{}", bytes);
        ByteBuffer byteBuffer = ByteBuffer.allocate(byteBuf.writerIndex());
        byteBuf.getBytes(0, byteBuffer);
        log.debug("byteBuffer:{}", byteBuffer.array());
        return byteBuffer;
    }

}

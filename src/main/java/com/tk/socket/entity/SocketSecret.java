package com.tk.socket.entity;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.ByteBuffer;

public class SocketSecret {

    private final SocketEncrypt encrypt;

    public SocketEncrypt getEncrypt() {
        return encrypt;
    }

    private final SocketDecrypt decrypt;

    public SocketDecrypt getDecrypt() {
        return decrypt;
    }

    private final GetEncrypt getEncrypt;

    public SocketEncrypt getNewEncrypt() {
        return getEncrypt.getEncrypt();
    }

    private final GetDecrypt getDecrypt;

    public SocketDecrypt getNewDecrypt() {
        return getDecrypt.getDecrypt();
    }

    public SocketSecret(GetEncrypt getEncrypt, GetDecrypt getDecrypt) {
        this.getEncrypt = getEncrypt;
        this.encrypt = getEncrypt.getEncrypt();
        this.getDecrypt = getDecrypt;
        this.decrypt = getDecrypt.getDecrypt();
    }

    public ByteBuf encode(ByteBuf byteBuf) {
        //TODO 优化修改
        return Unpooled.wrappedBuffer(encrypt.encode(toNioBuffer(byteBuf)));
    }

    public ByteBuf decode(ByteBuf byteBuf) {
        //TODO 优化修改
        return Unpooled.wrappedBuffer(decrypt.decode(toNioBuffer(byteBuf)));
    }

    @FunctionalInterface
    public interface GetEncrypt {
        SocketEncrypt getEncrypt();
    }

    @FunctionalInterface
    public interface GetDecrypt {
        SocketDecrypt getDecrypt();
    }

    public static ByteBuffer toNioBuffer(ByteBuf byteBuf) {
        int length = byteBuf.writerIndex();
        ByteBuffer byteBuffer;
        if (byteBuf.isDirect()) {
            byteBuffer = byteBuf.nioBuffer();
        } else {
            byteBuffer = ByteBuffer.allocate(length);
        }
        byteBuf.getBytes(0, byteBuffer);
        byteBuffer.flip();
        return byteBuffer;
    }

}

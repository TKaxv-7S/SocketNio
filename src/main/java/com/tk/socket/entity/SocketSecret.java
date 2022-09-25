package com.tk.socket.entity;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

public class SocketSecret {

    private final LoopArray<SocketEncrypt> encryptArray;

    public SocketEncrypt getEncryptArray() {
        return encryptArray.getByLoop();
    }

    private final LoopArray<SocketDecrypt> decryptArray;

    public SocketDecrypt getDecryptArray() {
        return decryptArray.getByLoop();
    }

    private final GetEncrypt getEncrypt;

    public SocketEncrypt getNewEncrypt() {
        return getEncrypt.get();
    }

    private final GetDecrypt getDecrypt;

    public SocketDecrypt getNewDecrypt() {
        return getDecrypt.get();
    }

    public SocketSecret(GetEncrypt getEncrypt, GetDecrypt getDecrypt) {
        this(getEncrypt, getDecrypt, 2);
    }

    public SocketSecret(GetEncrypt getEncrypt, GetDecrypt getDecrypt, int secretSize) {
        this.getEncrypt = getEncrypt;
        this.encryptArray = new LoopArray<>(getEncrypt, secretSize);
        this.getDecrypt = getDecrypt;
        this.decryptArray = new LoopArray<>(getDecrypt, secretSize);
    }

    public ByteBuf encode(ByteBuf byteBuf) {
        return Unpooled.wrappedBuffer(encryptArray.getByLoop().encode(toNioBuffer(byteBuf)));
    }

    public ByteBuf decode(ByteBuf byteBuf) {
        return Unpooled.wrappedBuffer(decryptArray.getByLoop().decode(toNioBuffer(byteBuf)));
    }

    @FunctionalInterface
    public interface GetEncrypt extends Supplier<SocketEncrypt> {
        SocketEncrypt get();
    }

    @FunctionalInterface
    public interface GetDecrypt extends Supplier<SocketDecrypt> {
        SocketDecrypt get();
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

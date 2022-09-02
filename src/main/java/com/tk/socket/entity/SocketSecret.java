package com.tk.socket.entity;

import com.tk.socket.SocketException;
import io.netty.buffer.ByteBuf;

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

    public byte[] encode(ByteBuf byteBuf) {
        return encode(byteBuf.array());
    }

    public byte[] encode(byte[] data) {
        try {
            return encode.encode(data);
        } catch (Exception e) {
            throw new SocketException(e, e.getMessage());
        }
    }

    public byte[] decode(ByteBuf byteBuf) {
        return decode(byteBuf.array());
    }

    public byte[] decode(byte[] data) {
        try {
            return decode.decode(data);
        } catch (Exception e) {
            throw new SocketException(e, e.getMessage());
        }
    }

    @FunctionalInterface
    public interface Encrypt {
        byte[] encode(byte[] data) throws Exception;
    }

    @FunctionalInterface
    public interface Decrypt {
        byte[] decode(byte[] data) throws Exception;
    }

}

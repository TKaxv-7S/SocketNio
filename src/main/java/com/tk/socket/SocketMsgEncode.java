package com.tk.socket;

@FunctionalInterface
public interface SocketMsgEncode {
    byte[] encode(byte[] data, byte[] secret);
}
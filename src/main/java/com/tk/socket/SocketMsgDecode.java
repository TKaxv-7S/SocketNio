package com.tk.socket;

@FunctionalInterface
public interface SocketMsgDecode {
    byte[] decode(byte[] data, byte[] secret);
}
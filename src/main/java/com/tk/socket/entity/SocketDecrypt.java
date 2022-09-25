package com.tk.socket.entity;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.ByteBuffer;

import static com.tk.socket.entity.SocketSecret.toNioBuffer;

public interface SocketDecrypt {

    ByteBuffer decode(ByteBuffer data);

    default ByteBuf decode(ByteBuf byteBuf) {
        return Unpooled.wrappedBuffer(decode(toNioBuffer(byteBuf)));
    }

}
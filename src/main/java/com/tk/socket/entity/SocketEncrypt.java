package com.tk.socket.entity;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.ByteBuffer;

import static com.tk.socket.entity.SocketSecret.toNioBuffer;

public interface SocketEncrypt {

    ByteBuffer encode(ByteBuffer data);

    default ByteBuf encode(ByteBuf byteBuf) {
        return Unpooled.wrappedBuffer(encode(toNioBuffer(byteBuf)));
    }

}
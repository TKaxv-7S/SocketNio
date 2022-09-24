package com.tk.socket.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

@Slf4j
public class SocketServerWrapMsgDto implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final byte DATA_START_BYTE = (byte) 0xA5;

    private final ByteBuf wrapMsg;

    public ByteBuf getWrapMsg() {
        return wrapMsg;
    }

    public SocketServerWrapMsgDto(ByteBuf data, byte secretByte) {
        int msgSize = data.writerIndex() + 8;
        byte msgSizeFirstByte = (byte) (msgSize >>> 24);
        this.wrapMsg = Unpooled.wrappedBuffer(
                Unpooled.wrappedBuffer(new byte[]{
                        DATA_START_BYTE,
                        msgSizeFirstByte,
                        (byte) (msgSize >>> 16),
                        (byte) (msgSize >>> 8),
                        (byte) msgSize
                }),
                data,
                Unpooled.wrappedBuffer(new byte[]{
                        msgSizeFirstByte,
                        DATA_START_BYTE,
                        secretByte})
        );
        wrapMsg.setByte(msgSize - 2, wrapMsg.getByte(msgSize / 2));
    }

    public byte[] toBytes() {
        byte[] src = new byte[wrapMsg.writerIndex()];
        wrapMsg.getBytes(0, src);
        return src;
    }
}

package com.tk.socket.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

@Slf4j
public class SocketServerWrapMsgDto implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final byte DATA_START_BYTE = (byte) 0xA5;

    //数据
    private final byte[] data;

    //加密字节，1字节
    private final byte secretByte;

    public SocketServerWrapMsgDto(byte[] data, byte secretByte) {
        this.data = data;
        this.secretByte = secretByte;
    }

    public ByteBuf toByteBuf() {
        int msgSize = data.length + 8;
        byte msgSizeFirstByte = (byte) (msgSize >>> 24);
        byte[] headBytes = {
                DATA_START_BYTE,
                msgSizeFirstByte,
                (byte) (msgSize >>> 16),
                (byte) (msgSize >>> 8),
                (byte) msgSize
        };
        byte[] bytes = {msgSizeFirstByte, DATA_START_BYTE, secretByte};
        ByteBuf byteBuf = Unpooled.wrappedBuffer(headBytes, data, bytes);
        byteBuf.setByte(msgSize - 2, byteBuf.getByte(msgSize / 2));
        /*if (log.isDebugEnabled()) {
            byte[] src = new byte[byteBuf.writerIndex()];
            byteBuf.getBytes(0, src);
            log.debug("封装报文：{}", src);
        }*/
        return byteBuf;
    }

    public byte[] toBytes() {
        ByteBuf byteBuf = toByteBuf();
        byte[] src = new byte[byteBuf.writerIndex()];
        byteBuf.getBytes(0, src);
        return src;
    }
}

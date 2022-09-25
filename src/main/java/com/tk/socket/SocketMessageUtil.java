package com.tk.socket;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class SocketMessageUtil {

    private static final byte dataStartByte = (byte) 0xA5;

    public static ByteBuf packageMsg(ByteBuf encode, byte secretByte) {
        int msgSize = encode.writerIndex() + 8;
        byte msgSizeFirstByte = (byte) (msgSize >>> 24);
        ByteBuf packageMsg = Unpooled.wrappedBuffer(
                Unpooled.wrappedBuffer(new byte[]{
                        dataStartByte,
                        msgSizeFirstByte,
                        (byte) (msgSize >>> 16),
                        (byte) (msgSize >>> 8),
                        (byte) msgSize}),
                encode,
                Unpooled.wrappedBuffer(new byte[]{
                        msgSizeFirstByte,
                        dataStartByte,
                        secretByte})
        );
        //TODO 检查
        packageMsg.setByte(msgSize - 2, packageMsg.getByte(msgSize / 2));
        /*if (log.isDebugEnabled()) {
            log.debug("封装报文 长度：{}，头部：{},{},{},{},{}，尾部：{},{},{}"
                    , msgSize
                    , packageMsg[0]
                    , packageMsg[1]
                    , packageMsg[2]
                    , packageMsg[3]
                    , packageMsg[4]
                    , packageMsg[msgSize - 3]
                    , packageMsg[msgSize - 2]
                    , packageMsg[msgSize - 1]
            );
        }*/
        return packageMsg;
    }

    public static int threeByteArrayToInt(byte[] byteArray) {
        return ((byteArray[0] & 0xFF) << 24) |
                ((byteArray[1] & 0xFF) << 16) |
                ((byteArray[2] & 0xFF) << 8) |
                (byteArray[2] & 0xFF);
    }

    public static ByteBuf packageData(ByteBuf data, boolean isAck) {
        ByteBuf ackData = Unpooled.buffer(3);
        int nextInt = ThreadLocalRandom.current().nextInt();
        if (isAck) {
            ackData.writeByte((byte) 0x80 | (byte) (nextInt >>> 25));
        } else {
            ackData.writeByte(((byte) 0x00 | (byte) (nextInt >>> 25)));
        }
        ackData.writeByte(nextInt >>> 16);
        ackData.writeByte(nextInt);
        return Unpooled.wrappedBuffer(ackData, data);
    }

    public static ByteBuf unPackageData(ByteBuf packageData) {
        return packageData.slice(3, packageData.writerIndex() - 3);
    }

    public static byte[] getAckData(ByteBuf packageData) {
        byte[] ackBytes = new byte[3];
        //把第一位设置成0，即无需接收端再ack
        ackBytes[0] = (byte) (packageData.getByte(0) & (byte) 0x7F);
        ackBytes[1] = packageData.getByte(1);
        ackBytes[2] = packageData.getByte(2);
        return ackBytes;
    }

    public static boolean isAckData(ByteBuf packageData) {
        //获取ack
        return (byte) 0x01 == (byte) ((packageData.getByte(0) & 0xFF) >>> 7);
    }

    public static int byteArrayToInt(ByteBuf byteArray) {
        return byteArray.getInt(0);
    }

    public static byte[] intToByteArray(int intValue) {
        return new byte[]{
                (byte) ((intValue >> 24) & 0xFF),
                (byte) ((intValue >> 16) & 0xFF),
                (byte) ((intValue >> 8) & 0xFF),
                (byte) (intValue & 0xFF)
        };
    }

}

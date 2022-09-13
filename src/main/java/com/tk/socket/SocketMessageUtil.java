package com.tk.socket;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class SocketMessageUtil {

    private static final byte dataStartByte = (byte) 0xA5;

    public static byte[] packageMsg(SocketWrapMsgDto socketWrapMsgDto) {
        return packageMsg(socketWrapMsgDto.getData(), socketWrapMsgDto.getSecretByte());
    }

    public static byte[] packageMsg(byte[] encode, byte secretByte) {
        int length = encode.length;
        int msgSize = length + 8;
        byte[] packageMsg = new byte[msgSize];
        System.arraycopy(encode, 0, packageMsg, 5, length);
        packageMsg[0] = dataStartByte;
        byte msgSizeFirstByte = (byte) (msgSize >>> 24);
        packageMsg[1] = msgSizeFirstByte;
        packageMsg[2] = (byte) (msgSize >>> 16);
        packageMsg[3] = (byte) (msgSize >>> 8);
        packageMsg[4] = (byte) msgSize;
        packageMsg[msgSize - 3] = msgSizeFirstByte;
        packageMsg[msgSize - 2] = packageMsg[msgSize / 2];
        packageMsg[msgSize - 1] = secretByte;
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

    public static byte[] packageData(byte[] data, boolean isAck) {
        int length = data.length;
        byte[] packageData = new byte[length + 3];
        System.arraycopy(data, 0, packageData, 3, length);
        int nextInt = ThreadLocalRandom.current().nextInt();
        if (isAck) {
            packageData[0] = (byte) ((byte) 0x80 | (byte) (nextInt >>> 25));
        } else {
            packageData[0] = (byte) ((byte) 0x00 | (byte) (nextInt >>> 25));
        }
        packageData[1] = (byte) (nextInt >>> 16);
        packageData[2] = (byte) (nextInt);
        return packageData;
    }

    public static byte[] unPackageData(byte[] packageData) {
        int length = packageData.length - 3;
        byte[] data = new byte[length];
        System.arraycopy(packageData, 3, data, 0, length);
        return data;
    }

    public static byte[] getAckData(byte[] packageData) {
        byte[] ackBytes = new byte[3];
        //把第一位设置成0，即无需接收端再ack
        ackBytes[0] = (byte) (packageData[0] & (byte) 0x7F);
        ackBytes[1] = packageData[1];
        ackBytes[2] = packageData[2];
        return ackBytes;
    }

    public static boolean isAckData(byte[] packageData) {
        //获取ack
        return (byte) 0x01 == (byte) ((packageData[0] & 0xFF) >>> 7);
    }

    public static int byteArrayToInt(byte[] byteArray) {
        return ((byteArray[0] & 0xFF) << 24) |
                ((byteArray[1] & 0xFF) << 16) |
                ((byteArray[2] & 0xFF) << 8) |
                (byteArray[3] & 0xFF);
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

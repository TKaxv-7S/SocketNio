package com.tk.socket.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tk.socket.*;
import com.tk.socket.entity.SocketSecret;
import com.tk.socket.utils.JsonUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
public abstract class SocketNioClient extends AbstractSocketNioClient {

    private final Integer msgSizeLimit;

    private final Map<String, SocketAckThreadDto> ackDataMap = new ConcurrentHashMap<>();

    private final Map<Integer, SocketMsgDataDto> syncDataMap = new ConcurrentHashMap<>();

    private final TypeReference<SocketMsgDataDto> socketDataDtoTypeReference = new TypeReference<SocketMsgDataDto>() {
    };

    private final AttributeKey<SocketParseMsgDto> msgKey = AttributeKey.valueOf("msg");

    private final SocketClientHandler socketClientHandler;

    private final byte[] appKey;

    private final SocketSecret secret;

    private final byte[] heartbeatBytes = JsonUtil.toJsonString(SocketMsgDataDto.build("", null)).getBytes(StandardCharsets.UTF_8);

    private Long heartbeatInterval;

    public SocketNioClient(SocketClientConfig config, SocketClientHandler socketClientHandler) {
        super(config);
        this.msgSizeLimit = Optional.ofNullable(config.getMsgSizeLimit()).orElse(4 * 1024 * 1024);
        this.socketClientHandler = socketClientHandler;
        Integer heartbeatInterval = config.getHeartbeatInterval();
        this.heartbeatInterval = Math.max(Objects.isNull(heartbeatInterval) ? 30000L : heartbeatInterval * 1000L, 15000L);
        connCallback = socketNioClient -> bootstrap.config().group().execute(() -> {
            Thread thread = Thread.currentThread();
            while (!thread.isInterrupted()) {
                try {
                    write(SocketMessageUtil.packageData(Unpooled.wrappedBuffer(heartbeatBytes), false));
                } catch (Exception e) {
                    log.warn("TCP客户端心跳异常", e);
                }
                try {
                    Thread.sleep(this.heartbeatInterval);
                } catch (InterruptedException e) {
                    log.warn("TCP客户端心跳线程已关闭");
                    return;
                }
            }
            log.warn("TCP客户端心跳线程已关闭");
        });
        this.appKey = config.getAppKey().getBytes(StandardCharsets.UTF_8);
        this.secret = config.getSecret();
    }

    @Override
    public Collection<ChannelHandler> setHandlers() {
        List<ChannelHandler> list = new ArrayList<>();
        list.add(new ClientInHandler());
        list.add(new ClientOutHandler());
        return list;
    }

    @Override
    public SocketClientWrapMsgDto encode(ByteBuf data) {
        return new SocketClientWrapMsgDto(secret.encode(data), appKey, (byte) 0xFF);
    }

    @Override
    public ByteBuf decode(ByteBuf data, byte secretByte) {
        return secret.decode(data);
    }

    @Override
    public void read(Channel channel, Object msg) {
        byte[] bytes = (byte[]) msg;
        SocketMsgDataDto socketDataDto = readSocketDataDto(bytes);
        Integer dataId = socketDataDto.getClientDataId();
        if (dataId != null) {
            SocketMsgDataDto syncDataDto = syncDataMap.get(dataId);
            if (syncDataDto != null) {
                synchronized (syncDataDto) {
                    syncDataDto.setData(socketDataDto.getData());
                    syncDataDto.setCode(socketDataDto.getCode());
                    syncDataDto.setMsg(socketDataDto.getMsg());
                    syncDataDto.setMethod("syncReturn");
                    syncDataDto.notify();
                }
            }
            //不再继续执行，即便method不为空
            return;
        }
        String method = socketDataDto.getMethod();
        if (StringUtils.isNotBlank(method)) {
            if (socketDataDto.isSuccess()) {
                Integer serverDataId = socketDataDto.getServerDataId();
                SocketMsgDataDto syncDataDto;
                try {
                    log.debug("服务端执行method：{}", method);
                    syncDataDto = socketClientHandler.handle(method, socketDataDto, this);
                } catch (Exception e) {
                    log.error("服务端执行method：{} 异常", method, e);
                    syncDataDto = SocketMsgDataDto.buildError(e.getMessage());
                }
                if (serverDataId != null) {
                    syncDataDto.setServerDataId(serverDataId);
                    write(syncDataDto);
                }
            } else {
                log.error("服务端处理失败：{}", JsonUtil.toJsonString(socketDataDto));
            }
        }
        log.debug("客户端已读");
    }

    @Override
    protected void channelRegisteredEvent(ChannelHandlerContext ctx) {
        super.channelRegisteredEvent(ctx);
        Channel channel = ctx.channel();
        Attribute<SocketParseMsgDto> socketMsgDtoAttribute = channel.attr(msgKey);
        socketMsgDtoAttribute.setIfAbsent(new SocketParseMsgDto(msgSizeLimit, 30));
    }

    @Override
    protected void channelUnregisteredEvent(ChannelHandlerContext ctx) {
        super.channelUnregisteredEvent(ctx);
        Channel channel = ctx.channel();
        Attribute<SocketParseMsgDto> socketMsgDtoAttribute = channel.attr(msgKey);
        SocketParseMsgDto socketParseMsgDto = socketMsgDtoAttribute.getAndSet(null);
        try {
            if (socketParseMsgDto != null) {
                socketParseMsgDto.release();
            }
        } catch (Exception e) {
            log.error("消息释放异常", e);
        }
    }

    public void write(SocketJSONDataDto data) {
        write(SocketMessageUtil.packageData(Unpooled.wrappedBuffer(JsonUtil.toJsonString(data).getBytes(StandardCharsets.UTF_8)), false));
    }

    public boolean writeAck(ByteBuf data, int seconds) {
        Channel channel = channelPool.borrowChannel();
        try {
            seconds = Math.min(seconds, 10);
            ByteBuf packageData = SocketMessageUtil.packageData(data, true);
            byte[] needAckBytes = {(byte) (packageData.getByte(0) & (byte) 0x7F), packageData.getByte(1), packageData.getByte(2)};
            int ackKey = SocketMessageUtil.threeByteArrayToInt(needAckBytes);
            String key = Integer.toString(ackKey).concat(channel.id().asShortText());
            try {
                SocketAckThreadDto ackThreadDto = new SocketAckThreadDto();
                ackDataMap.put(key, ackThreadDto);
                synchronized (ackThreadDto) {
                    channel.writeAndFlush(packageData);
                    log.debug("等待ack字节：{}", needAckBytes);
                    ackThreadDto.wait(TimeUnit.SECONDS.toMillis(seconds));
                }
//            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(Math.min(seconds, 10)));
                SocketAckThreadDto socketAckThreadDto = ackDataMap.remove(key);
                return socketAckThreadDto != null && socketAckThreadDto.getIsAck();
            } catch (InterruptedException e) {
                log.error("同步写异常", e);
                ackDataMap.remove(key);
                throw new SocketException("同步写异常");
            } catch (Exception e) {
                log.error("同步写异常", e);
                ackDataMap.remove(key);
                throw e;
            }
        } finally {
            channelPool.returnChannel(channel);
        }
    }

    public boolean writeAck(ByteBuf data) {
        return writeAck(data, 10);
    }

    public boolean writeAck(SocketJSONDataDto data) {
        return writeAck(Unpooled.wrappedBuffer(JsonUtil.toJsonString(data).getBytes(StandardCharsets.UTF_8)));
    }

    public boolean writeAck(SocketJSONDataDto data, int seconds) {
        return writeAck(Unpooled.wrappedBuffer(JsonUtil.toJsonString(data).getBytes(StandardCharsets.UTF_8)), seconds);
    }

    public SocketMsgDataDto writeSync(SocketMsgDataDto data) {
        return writeSync(data, 10);
    }

    public SocketMsgDataDto writeSync(SocketMsgDataDto data, int seconds) {
        Integer dataId = data.getClientDataId();
        if (dataId == null) {
            dataId = ThreadLocalRandom.current().nextInt();
            data.setClientDataId(dataId);
        }
        SocketMsgDataDto syncDataDto = new SocketMsgDataDto(dataId, true);
        syncDataMap.put(dataId, syncDataDto);
        try {
            synchronized (syncDataDto) {
                write(SocketMessageUtil.packageData(Unpooled.wrappedBuffer(JsonUtil.toJsonString(data).getBytes(StandardCharsets.UTF_8)), false));
                syncDataDto.wait(TimeUnit.SECONDS.toMillis(seconds));
            }
            SocketMsgDataDto socketDataDto = syncDataMap.remove(dataId);
            String method = socketDataDto.getMethod();
            if (!StringUtils.equals(method, "syncReturn")) {
                throw new SocketException("写入超时");
            }
            return socketDataDto;
        } catch (InterruptedException e) {
            log.error("同步写入异常", e);
            syncDataMap.remove(dataId);
            throw new SocketException("同步写入异常");
        } catch (Exception e) {
            log.error("同步写入异常", e);
            syncDataMap.remove(dataId);
            throw e;
        }
    }

    @ChannelHandler.Sharable
    class ClientInHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            super.channelRegistered(ctx);
            channelRegisteredEvent(ctx);
        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
            super.channelUnregistered(ctx);
            channelUnregisteredEvent(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msgObj) throws Exception {
            ByteBuf msg = (ByteBuf) msgObj;

            Channel socketChannel = ctx.channel();
            ChannelId channelId = socketChannel.id();
            Attribute<SocketParseMsgDto> socketMsgDtoAttribute = socketChannel.attr(msgKey);
            ByteBuf leftMsg = msg;
            SocketParseMsgDto socketParseMsgDto = socketMsgDtoAttribute.get();
            try {
                do {
                    synchronized (socketParseMsgDto) {
                        leftMsg = socketParseMsgDto.parsingMsg(leftMsg);
                    }
                    if (!socketParseMsgDto.getDone()) {
                        break;
                    }
                    Byte secretByte = socketParseMsgDto.getSecretByte();
                    //读取完成，写入队列
                    ByteBuf decodeBytes;
                    try {
                        decodeBytes = decode(socketParseMsgDto.getMsg(), secretByte);
                    } catch (Exception e) {
                        log.debug("解码错误", e);
                        //丢弃并关闭连接
                        throw new SocketException("报文解码错误");
                    }

                    int length = decodeBytes.writerIndex();
                    boolean sendOrReceiveAck = SocketMessageUtil.isAckData(decodeBytes);
                    if (length > 3) {
                        log.debug("数据已接收，channelId：{}", channelId);
                        ByteBuf unPackageData = SocketMessageUtil.unPackageData(decodeBytes);
                        byte[] bytes = new byte[unPackageData.writerIndex()];
                        unPackageData.getBytes(0, bytes);
                        msgHandler.read(ctx.channel(), bytes);
                        if (sendOrReceiveAck) {
                            byte[] ackBytes = SocketMessageUtil.getAckData(decodeBytes);
                            try {
                                socketChannel.writeAndFlush(ackBytes);
                                //log.debug("发送ack字节：{}", ackBytes);
                            } catch (Exception e) {
                                log.error("ack编码错误", e);
                            }
                        }
                    } else {
                        if (!sendOrReceiveAck) {
                            //接收ackBytes
                            byte[] ackBytes = SocketMessageUtil.getAckData(decodeBytes);
                            int ackKey = SocketMessageUtil.threeByteArrayToInt(ackBytes);
                            String key = Integer.toString(ackKey).concat(channelId.asShortText());
                            SocketAckThreadDto socketAckThreadDto = ackDataMap.get(key);
                            if (socketAckThreadDto != null) {
                                synchronized (socketAckThreadDto) {
                                    socketAckThreadDto.setIsAck(true);
                                    socketAckThreadDto.notify();
                                }
//                            LockSupport.unpark(socketAckThreadDto.getThread());
                                //log.debug("接收ack字节：{}，已完成", ackBytes);
                            } else {
                                log.error("接收ack字节：{}，未命中或请求超时", ackBytes);
                            }
                        } else {
                            //关闭连接
                            throw new SocketException("报文数据异常");
                        }
                    }
                    socketParseMsgDto.clear();
                } while (leftMsg != null);
            } catch (SocketException e) {
                log.error("数据解析异常：{}", e.getMessage());
                //丢弃数据并关闭连接
                socketChannel.close();
                //异常丢弃
                socketParseMsgDto.release();
                while (msg.refCnt() > 0) {
                    ReferenceCountUtil.release(msg);
                }
            } catch (Exception e) {
                log.error("数据解析异常", e);
                //丢弃数据并关闭连接
                socketChannel.close();
                //异常丢弃
                socketParseMsgDto.release();
                while (msg.refCnt() > 0) {
                    ReferenceCountUtil.release(msg);
                }
            }
        }
    }

    @ChannelHandler.Sharable
    class ClientOutHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            ByteBuf data;
            if (msg instanceof ByteBuf) {
                data = (ByteBuf) msg;
            } else if (msg instanceof byte[]) {
                data = Unpooled.wrappedBuffer((byte[]) msg);
            } else {
                //传输其他类型数据时暂不支持ACK，需使用ByteBuf或byte[]
                data = SocketMessageUtil.packageData(Unpooled.wrappedBuffer(JsonUtil.toJsonString(msg).getBytes(StandardCharsets.UTF_8)), false);
            }
            ctx.writeAndFlush(encode(data).getWrapMsg(), promise);
            log.debug("数据已发送，channelId：{}", ctx.channel().id());
        }
    }


    public void setHeartbeatInterval(Integer heartbeatInterval) {
        Integer oldHeartbeatInterval = getHeartbeatInterval();
        this.heartbeatInterval = Math.max(Objects.isNull(heartbeatInterval) ? 30000L : heartbeatInterval * 1000L, 15000L);
        log.info("TCP客户端心跳间隔时间已更新，旧：{}秒，新：{}秒", oldHeartbeatInterval, heartbeatInterval);
    }

    public Integer getHeartbeatInterval() {
        return ((Long) (heartbeatInterval / 1000)).intValue();
    }

    public SocketMsgDataDto readSocketDataDto(byte[] data) {
        return JsonUtil.parseObject(new String(data, StandardCharsets.UTF_8), socketDataDtoTypeReference);
    }
}

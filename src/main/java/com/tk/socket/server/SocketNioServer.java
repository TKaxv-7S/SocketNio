package com.tk.socket.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tk.socket.*;
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
public abstract class SocketNioServer<T extends SocketClientCache<? extends SocketServerSecretDto>> extends AbstractSocketNioServer implements SocketNioServerWrite {

    private final Integer msgSizeLimit;

    private final Map<String, SocketAckThreadDto> ackDataMap = new ConcurrentHashMap<>();

    private final Map<Integer, SocketMsgDataDto> syncDataMap = new ConcurrentHashMap<>();

    private final TypeReference<SocketMsgDataDto> socketDataDtoTypeReference = new TypeReference<SocketMsgDataDto>() {
    };

    private final AttributeKey<SocketParseMsgDto> msgKey = AttributeKey.valueOf("msg");

    protected final SocketServerHandler socketServerHandler;

    protected final T socketClientCache;

    public T getSocketClientCache() {
        return socketClientCache;
    }

    public SocketNioServer(SocketServerConfig config, SocketServerHandler socketServerHandler, T socketClientCache) {
        super(config);
        this.msgSizeLimit = Optional.ofNullable(config.getMsgSizeLimit()).orElse(4 * 1024 * 1024);
        this.socketServerHandler = socketServerHandler;
        this.socketClientCache = socketClientCache;
        /*this.readCacheMap = Caffeine.newBuilder()
                .expireAfterAccess(10, TimeUnit.SECONDS)
                .removalListener((ChannelId key, SocketMsgDto value, RemovalCause removalCause) -> {
                    if (value != null) {
                        CompositeByteBuf msg = value.getFull();
                        while (msg.refCnt() > 0) {
                            int numComponents = msg.numComponents();
                            for (int i = 0; i < numComponents; i++) {
                                ByteBuf component = msg.component(i);
                                log.info("component index:{}，refCnt:{}", i, component.refCnt());
                            }
                            ReferenceCountUtil.release(msg);
                        }
                    }
                })
                .build();*/
    }

    @Override
    public Collection<ChannelHandler> setHandlers() {
        List<ChannelHandler> list = new ArrayList<>();
        list.add(new ServerInHandler());
        list.add(new ServerOutHandler());
        return list;
    }

    @Override
    public ByteBuf encode(Channel channel, ByteBuf data) {
        SocketServerSecretDto secret = socketClientCache.getSecret(channel);
        if (secret != null) {
            return new SocketServerWrapMsgDto(secret.encode(data), (byte) 0xFF).getWrapMsg();
        }
        return null;
    }

    @Override
    public ByteBuf decode(Channel channel, ByteBuf data) {
        int appKeyLength = SocketMessageUtil.byteArrayToInt(data);
        String appKey = socketClientCache.getAppKey(channel);
        if (appKey == null) {
            byte[] appKeyBytes = new byte[appKeyLength];
            data.getBytes(4, appKeyBytes);
            String clientKey = new String(appKeyBytes, StandardCharsets.UTF_8);
            appKey = SocketServerChannel.getAppKey(clientKey);
            SocketServerSecretDto secret = socketClientCache.getSecret(appKey);
            if (secret != null) {
                int index = 4 + appKeyLength;
                ByteBuf bytes = secret.decode(data.slice(index, data.writerIndex() - index));
                if (!socketClientCache.addChannel(clientKey, channel, this)) {
                    log.error("clientKey：{}，客户端连接数超出限制", clientKey);
                    throw new SocketException("客户端连接数超出限制");
                }
                return bytes;
            }
        } else {
            SocketServerSecretDto secret = socketClientCache.getSecret(appKey);
            if (secret != null) {
                int index = 4 + appKeyLength;
                return secret.decode(data.slice(index, data.writerIndex() - index));
            }
        }
        throw new SocketException("客户端未配置");
    }

    @Override
    public void read(Channel channel, Object msg) {
        byte[] bytes = (byte[]) msg;
        SocketMsgDataDto socketDataDto = readSocketDataDto(bytes);
        Integer serverDataId = socketDataDto.getServerDataId();
        if (serverDataId != null) {
            SocketMsgDataDto syncDataDto = syncDataMap.get(serverDataId);
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
                Integer clientDataId = socketDataDto.getClientDataId();
                SocketMsgDataDto syncDataDto;
                try {
                    log.debug("客户端执行method：{}", method);
                    syncDataDto = socketServerHandler.handle(method, socketDataDto, socketClientCache.getChannel(channel));
                } catch (Exception e) {
                    log.error("客户端执行method：{} 异常", method, e);
                    syncDataDto = SocketMsgDataDto.buildError(e.getMessage());
                }
                if (clientDataId != null) {
                    syncDataDto.setClientDataId(clientDataId);
                    write(syncDataDto, channel);
                }
            } else {
                log.error("客户端处理失败：{}", JsonUtil.toJsonString(socketDataDto));
            }
        } else {
            //客户端心跳
            socketClientCache.getChannel(channel);
        }
    }

    @Override
    protected void channelRegisteredEvent(ChannelHandlerContext ctx) {
        super.channelRegisteredEvent(ctx);
        Channel channel = ctx.channel();
        Attribute<SocketParseMsgDto> socketMsgDtoAttribute = channel.attr(msgKey);
        socketMsgDtoAttribute.setIfAbsent(new SocketParseMsgDto(msgSizeLimit, 10));
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
        socketClientCache.delChannel(channel);
    }

    public void write(SocketMsgDataDto data, Channel channel) {
        write(channel, SocketMessageUtil.packageData(Unpooled.wrappedBuffer(JsonUtil.toJsonString(data).getBytes(StandardCharsets.UTF_8)), false));
    }

    /**
     * 服务端等待确认时间最久10秒
     *
     * @param socketChannel
     * @param data
     * @param seconds
     * @return
     */
    public boolean writeAck(Channel socketChannel, ByteBuf data, int seconds) {
        seconds = Math.min(seconds, 10);
        ByteBuf packageData = SocketMessageUtil.packageData(data, true);
        byte[] needAckBytes = {(byte) (packageData.getByte(0) & (byte) 0x7F), packageData.getByte(1), packageData.getByte(2)};
        int ackKey = SocketMessageUtil.threeByteArrayToInt(needAckBytes);
        String key = Integer.toString(ackKey).concat(socketChannel.id().asShortText());
        try {
            SocketAckThreadDto ackThreadDto = new SocketAckThreadDto();
            ackDataMap.put(key, ackThreadDto);
            synchronized (ackThreadDto) {
                socketChannel.writeAndFlush(packageData);
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
    }

    public boolean writeAck(Channel socketChannel, ByteBuf data) {
        return writeAck(socketChannel, data, 10);
    }

    public boolean writeAck(SocketMsgDataDto data, Channel channel, int seconds) {
        return writeAck(channel, Unpooled.wrappedBuffer(JsonUtil.toJsonString(data).getBytes(StandardCharsets.UTF_8)), seconds);
    }

    public boolean writeAck(SocketMsgDataDto data, Channel channel) {
        return writeAck(channel, Unpooled.wrappedBuffer(JsonUtil.toJsonString(data).getBytes(StandardCharsets.UTF_8)));
    }

    public SocketMsgDataDto writeSync(SocketMsgDataDto data, Channel channel) {
        return writeSync(data, 10, channel);
    }

    public SocketMsgDataDto writeSync(SocketMsgDataDto data, int seconds, Channel channel) {
        Integer dataId = data.getServerDataId();
        if (dataId == null) {
            dataId = ThreadLocalRandom.current().nextInt();
            data.setServerDataId(dataId);
        }
        SocketMsgDataDto syncDataDto = new SocketMsgDataDto(dataId, false);
        syncDataMap.put(dataId, syncDataDto);
        try {
            synchronized (syncDataDto) {
                write(channel, SocketMessageUtil.packageData(Unpooled.wrappedBuffer(JsonUtil.toJsonString(data).getBytes(StandardCharsets.UTF_8)), false));
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
    class ServerInHandler extends ChannelInboundHandlerAdapter {

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
                    //读取完成，写入队列
                    ByteBuf decodeBytes;
                    try {
                        decodeBytes = decode(ctx.channel(), socketParseMsgDto.getMsg());
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
    class ServerOutHandler extends ChannelOutboundHandlerAdapter {

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
            ctx.writeAndFlush(encode(ctx.channel(), data), promise);
            log.debug("数据已发送，channelId：{}", ctx.channel().id());
        }
    }

    @Override
    public Boolean isClient(Channel channel) {
        return SocketServerChannel.hasClientKey(channel);
    }

    public SocketMsgDataDto readSocketDataDto(byte[] data) {
        return JsonUtil.parseObject(new String(data, StandardCharsets.UTF_8), socketDataDtoTypeReference);
    }
}

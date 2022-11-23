package com.tk.socket.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tk.socket.SocketException;
import com.tk.socket.SocketMessageUtil;
import com.tk.socket.SocketMsgDataDto;
import com.tk.socket.utils.JsonUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

@Slf4j
public abstract class SocketNioServer<T extends SocketClientCache<? extends SocketServerSecretDto>> extends AbstractSocketNioServer implements SocketNioServerWrite {

    private final SocketServerHandler socketServerHandler;

    private final T socketClientCache;

    private final Map<Integer, SocketMsgDataDto> syncDataMap = new ConcurrentHashMap<>();

    private final TypeReference<SocketMsgDataDto> socketDataDtoTypeReference = new TypeReference<SocketMsgDataDto>() {
    };

    public T getSocketClientCache() {
        return socketClientCache;
    }

    public SocketNioServer(SocketServerConfig config, SocketServerHandler socketServerHandler, T socketClientCache) {
        super(config);
        this.socketServerHandler = socketServerHandler;
        this.socketClientCache = socketClientCache;
    }

    public void write(SocketMsgDataDto data, Channel channel) {
        write(channel, Unpooled.wrappedBuffer(JsonUtil.toJsonString(data).getBytes(StandardCharsets.UTF_8)));
    }

    public boolean writeAck(SocketMsgDataDto data, Channel channel) {
        return writeAck(channel, Unpooled.wrappedBuffer(JsonUtil.toJsonString(data).getBytes(StandardCharsets.UTF_8)));
    }

    public boolean writeAck(SocketMsgDataDto data, Channel channel, int seconds) {
        return writeAck(channel, Unpooled.wrappedBuffer(JsonUtil.toJsonString(data).getBytes(StandardCharsets.UTF_8)), seconds);
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
                write(channel, Unpooled.wrappedBuffer(JsonUtil.toJsonString(data).getBytes(StandardCharsets.UTF_8)));
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

    @Override
    public BiConsumer<ChannelHandlerContext, byte[]> setDataConsumer() {
        return (ctx, bytes) -> {
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
                    Channel channel = ctx.channel();
                    Integer clientDataId = socketDataDto.getClientDataId();
                    SocketMsgDataDto syncDataDto;
                    try {
                        log.debug("客户端执行method：{}", method);
                        syncDataDto = socketServerHandler.handle(method, socketDataDto, socketClientCache.getChannel(channel));
                    } catch (Exception e) {
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
                socketClientCache.getChannel(ctx.channel());
            }
        };
    }

    @Override
    public Boolean isClient(Channel channel) {
        return socketClientCache.hasClientKey(channel);
    }

    public SocketMsgDataDto readSocketDataDto(byte[] data) {
        return JsonUtil.parseObject(new String(data, StandardCharsets.UTF_8), socketDataDtoTypeReference);
    }

    @Override
    public SocketServerWrapMsgDto encode(Channel channel, ByteBuf data) {
        SocketServerSecretDto secret = socketClientCache.getSecret(channel);
        if (secret != null) {
            return new SocketServerWrapMsgDto(secret.encode(data), (byte) 0xFF);
        }
        return null;
    }

    @Override
    public ByteBuf decode(Channel channel, ByteBuf data, byte secretByte) {
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
    protected void channelUnregisteredEvent(ChannelHandlerContext ctx) {
        socketClientCache.delChannel(ctx.channel());
        super.channelUnregisteredEvent(ctx);
    }
}
